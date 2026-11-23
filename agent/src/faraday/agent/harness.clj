;; faraday-agent operator harness: launch/steer/observe N agents.
;; The operator control surface: launch N agents (each running a loop-as-data
;; manifest over its own rap server), hand them a shared goal/plan, steer them
;; mid-run with directives or a :goal stop-condition, and observe them through
;; the ledger and the memory. The pool+plane are wired in through the :server-fn
;; seam, so the harness composes them without owning their lifecycle.
(ns faraday.agent.harness
  "Operator control surface: launch/steer/observe N agents.

  launch! runs N loop-as-data manifests, one rap server each; steer! sends a
  mid-run directive, updates the :goal stop-condition, or cancels; observe
  reads one agent's status, directives, ledger history, salient/breadcrumb
  memory, and the memo keys its thread referenced. The sandbox pool and the
  plane are composed in through :server-fn — (fn [] -> rap server) — which is
  where pool/acquire and the plane gate live."
  (:require [faraday.agent :as agent]
            [faraday.agent.loop :as loop]
            [faraday.ledger :as ledger]
            [faraday.fanout :as fanout]
            [faraday.memo :as memo]
            [faraday.memory :as memory]
            [faraday.rap :as rap]))

(defrecord Harness [opts agents])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.harness/error true))))

(defn- hopts [h k] (get-in h [:opts k]))

(defn- now [] (str (java.time.Instant/now)))

(defn new-harness
  "Open a harness over opts: :ledger (faraday-ledger), :memo (faraday-memo),
  :memory (faraday-memory), :fanout (fanout runtime), and :server-fn (fn [] ->
  rap server). Returns an opaque harness handle."
  [opts]
  (when-not (:ledger opts) (fail! "new-harness requires a :ledger" {:opts opts}))
  (when-not (:memo opts) (fail! "new-harness requires a :memo" {:opts opts}))
  (when-not (:memory opts) (fail! "new-harness requires a :memory" {:opts opts}))
  (when-not (:fanout opts) (fail! "new-harness requires a :fanout" {:opts opts}))
  (when-not (ifn? (:server-fn opts))
    (fail! "new-harness requires a :server-fn (fn [] -> rap server)" {:opts opts}))
  (->Harness opts (atom {})))

(defn launch!
  "Launch N agents, each running the same loop-as-data manifest over its own
  rap server, handed the shared :task (the goal/plan). Returns a vector of
  agent control maps {:thread-id :goal-ref :status-ref :outcome-ref :server
  :directives :future}. The :goal stop-condition starts from the manifest's
  declarative :goal and is steerable mid-run.

  opts: :n (default 1), :manifest or (:memo + :key), :task, :thread-id (base;
  when n>1 each agent gets -i)."
  [harness opts]
  (let [n (or (:n opts) 1)
        m (or (:manifest opts)
              (loop/load-manifest (hopts harness :memo) (:key opts)))
        base-tid (or (:thread-id opts)
                     (str "agent_" (java.util.UUID/randomUUID)))
        _ (agent/warm-jdk-providers!)]
    (when-not m (fail! "launch! requires :manifest or (:memo + :key)" {:opts opts}))
    (mapv (fn [i]
            (let [tid (if (> n 1) (str base-tid "-" i) base-tid)
                  srv ((hopts harness :server-fn))
                  goal-ref (atom (when-let [g (:goal m)] (loop/goal-fn g)))
                  ctrl {:thread-id tid
                        :server srv
                        :goal-ref goal-ref
                        :directives (atom [])
                        :status-ref (atom :launched)
                        :outcome-ref (atom nil)}
                  fut (future
                        (try
                          (let [out (loop/run-manifest!
                                     {:rap srv :manifest m
                                      :task (or (:task opts) (:task m))
                                      :thread-id tid
                                      :goal (fn [ctx]
                                              (when-let [g @goal-ref] (g ctx)))})]
                            (reset! (:status-ref ctrl) (:status out))
                            (reset! (:outcome-ref ctrl) out)
                            out)
                          (catch Exception e
                            (let [out {:status :error :error (ex-message e)}]
                              (reset! (:status-ref ctrl) :error)
                              (reset! (:outcome-ref ctrl) out)
                              out))))]
              (swap! (:agents harness) assoc tid (assoc ctrl :future fut))
              ctrl))
          (range n))))

(defn steer!
  "Steer a running agent by thread-id. opts carries exactly one steering signal:
    :goal       a predicate fn, or a declarative goal spec (loop/goal-fn) —
                replaces the mid-run stop-condition
    :cancel     true — stop the agent at the next goal check
    :user-input a string — record a durable directive and emit a user-input
                Thread Message into the thread
  Returns the agent control map."
  [harness thread-id opts]
  (let [ctrl (get @(:agents harness) thread-id)]
    (when-not ctrl (fail! (str "unknown agent thread-id: " thread-id) {:thread-id thread-id}))
    (cond
      (:cancel opts)
      (do (reset! (:goal-ref ctrl) (constantly true))
          (swap! (:directives ctrl) conj :cancel))

      (contains? opts :goal)
      (reset! (:goal-ref ctrl)
              (if (fn? (:goal opts))
                (:goal opts)
                (loop/goal-fn (:goal opts))))

      (:user-input opts)
      (let [content (:user-input opts)
            rt (hopts harness :fanout)]
        (swap! (:directives ctrl) conj content)
        (fanout/emit rt {:v 1
                         :thread-id thread-id
                         :parent-id nil
                         :msg-id (str "msg_" (java.util.UUID/randomUUID))
                         :kind "user-input"
                         :ts (now)
                         :payload {:content content}})))
    ctrl))

(defn observe
  "Observe one agent by thread-id: {:thread-id :status :directives :outcome
  :history :salient :breadcrumbs :memo-keys}. Reads the ledger and the memory,
  so the control surface sees exactly what the plane recorded."
  [harness thread-id]
  (let [ctrl (get @(:agents harness) thread-id)
        ledg (hopts harness :ledger)
        memy (hopts harness :memory)
        history (ledger/history ledg thread-id)]
    (cond-> {:thread-id thread-id
             :history history
             :salient (memory/salient memy thread-id)
             :breadcrumbs (memory/breadcrumbs memy thread-id)
             :memo-keys (vec (keep #(get-in % [:payload :memo-key]) history))}
      ctrl (assoc :status @(:status-ref ctrl)
                  :directives @(:directives ctrl)
                  :outcome @(:outcome-ref ctrl)))))

(defn agents
  "The launched agents: a vector of {:thread-id :status}."
  [harness]
  (mapv (fn [[tid c]] {:thread-id tid :status @(:status-ref c)})
        @(:agents harness)))

(defn wait-all
  "Block until every launched agent reaches a terminal status. Returns one
  observe map per agent, in launch order."
  [harness]
  (let [cs (vals @(:agents harness))]
    (doseq [c cs] (deref (:future c)))
    (mapv (fn [c] (observe harness (:thread-id c))) cs)))

(defn shutdown!
  "Cancel every launched agent (stop at the next goal check) and close their
  rap servers. Does not close the ledger/memo/memory the harness was given."
  [harness]
  (doseq [c (vals @(:agents harness))]
    (reset! (:goal-ref c) (constantly true))
    (try (rap/close! (:server c)) (catch Exception _ nil)))
  :shutdown)
