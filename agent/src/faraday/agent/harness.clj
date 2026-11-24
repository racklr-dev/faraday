;; faraday-agent operator harness: launch/steer/observe N agents over the
;; yieldable scheduler + middleware spine.
;; The operator control surface: launch N agents (each a yieldable loop parked
;; as data between turns), hand them a shared goal/plan, steer them mid-run
;; with directives or a :goal stop-condition, and observe them through the
;; ledger and the two-tier memory. The harness owns a faraday.agent.scheduler
;; over the caller's thread-keyed rap server, thread store, fanout runtime and
;; memory, so it composes the scheduler+yield+mw base without owning the
;; sandbox pool's lifecycle.
(ns faraday.agent.harness
  "Operator control surface: launch/steer/observe N agents over the yieldable
  scheduler + middleware spine.

  launch! compiles a loop-as-data manifest through faraday.agent.mw and starts
  N yieldable loops on a faraday.agent.scheduler (one thread-keyed rap server,
  each agent parked as data while its tool result is in flight); steer! sends a
  mid-run directive, replaces the :goal stop-condition, or cancels; observe
  reads one agent's scheduler status, directives, ledger history,
  salient/breadcrumb memory, and the memo keys its thread referenced. The
  caller supplies :srv (thread-keyed rap), :store (faraday.thread store),
  :rt (fanout runtime) and optional :mem (faraday-memory); the harness owns
  only the scheduler it builds over them."
  (:require [faraday.agent :as agent]
            [faraday.agent.loop :as loop]
            [faraday.agent.mw :as mw]
            [faraday.agent.scheduler :as sched]
            [faraday.ledger :as ledger]
            [faraday.fanout :as fanout]
            [faraday.memory :as memory]
            [faraday.rap :as rap]))

(defrecord Harness [opts scheduler agents order])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.harness/error true))))

(defn- now [] (str (java.time.Instant/now)))

(defn new-harness
  "Open a harness over opts: :srv (a thread-keyed faraday-rap server, e.g. one
  opened with :run-hook (faraday.thread/rap-hook store)), :store (a
  faraday.thread store), :rt (a faraday-fanout runtime), and optional :mem (a
  faraday-memory handle for durable checkpoints and observe). Returns an
  opaque harness handle that owns a scheduler over those deps."
  [opts]
  (when-not (:srv opts)
    (fail! "new-harness requires a :srv (thread-keyed faraday-rap server)" {:opts opts}))
  (when-not (:store opts)
    (fail! "new-harness requires a :store (faraday.thread store)" {:opts opts}))
  (when-not (:rt opts)
    (fail! "new-harness requires a :rt (faraday-fanout runtime)" {:opts opts}))
  (->Harness opts
             (sched/new-scheduler (cond-> {:srv (:srv opts)
                                           :store (:store opts)
                                           :rt (:rt opts)}
                                    (:mem opts) (assoc :mem (:mem opts))))
             (atom {})
             (atom [])))

(defn- compile-step
  "Compile the manifest through the middleware spine over the harness's deps.
  A caller-supplied :step-fn wins; otherwise the memory handle (when present)
  and a caller-supplied :model-agent (for :model manifests) are the only deps.
  The :goal stop-condition stays at the yield-loop level (steerable per thread),
  so wrap-goal is intentionally not given a :goal-fn here."
  [harness opts m]
  (or (:step-fn opts)
      (mw/compile-step-fn
       m
       (cond-> {:memory (:mem (:opts harness))}
         (:model-agent (:opts harness)) (assoc :model-agent (:model-agent (:opts harness)))))))

(defn launch!
  "Launch N agents, each a yieldable loop of the same loop-as-data manifest on
  the harness's scheduler. Each agent parks as data while its tool result is in
  flight; the scheduler resumes it on the rap result callback, so no live
  future holds a waiting agent. Returns a vector of agent control maps
  {:thread-id :goal-ref :directives}. The :goal stop-condition starts from the
  manifest's declarative :goal and is steerable mid-run.

  opts: :n (default 1), :manifest or (:memo + :key), :task, and optional
  :step-fn (a compiled faraday.agent.mw step, wins over the default compile)."
  [harness opts]
  (let [n (or (:n opts) 1)
        m (or (:manifest opts)
              (loop/load-manifest (:memo opts) (:key opts)))
        task (or (:task opts) (:task m))]
    (when-not m (fail! "launch! requires :manifest or (:memo + :key)" {:opts opts}))
    (agent/warm-jdk-providers!)
    (let [goal-refs (mapv (fn [_] (atom (when-let [g (:goal m)] (loop/goal-fn g)))) (range n))
          directives (mapv (fn [_] (atom [])) (range n))
          step-fns (mapv (fn [_] (compile-step harness opts m)) (range n))
          goal-fns (mapv (fn [r] (fn [ctx] (when-let [g @r] (g ctx)))) goal-refs)
          tids (sched/launch! (:scheduler harness)
                              {:manifest m :n n :task task
                               :step-fns step-fns :goal-fns goal-fns})]
      (mapv (fn [i]
              (let [tid (nth tids i)
                    ctrl {:thread-id tid :goal-ref (nth goal-refs i)
                          :directives (nth directives i)}]
                (swap! (:agents harness) assoc tid ctrl)
                (swap! (:order harness) conj tid)
                ctrl))
            (range n)))))

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
            rt (:rt (:opts harness))]
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
  :history :salient :breadcrumbs :memo-keys}. Reads the scheduler loop state,
  the ledger, and the memory, so the control surface sees exactly what the
  plane recorded."
  [harness thread-id]
  (let [ctrl (get @(:agents harness) thread-id)
        so (sched/observe (:scheduler harness) thread-id)
        rt (:rt (:opts harness))
        ledg (:ledger rt)
        memy (:mem (:opts harness))
        history (when ledg (ledger/history ledg thread-id))]
    (cond-> {:thread-id thread-id
             :history history
             :salient (when memy (memory/salient memy thread-id))
             :breadcrumbs (when memy (memory/breadcrumbs memy thread-id))
             :memo-keys (vec (keep #(get-in % [:payload :memo-key]) history))}
      ctrl (assoc :status (:status so)
                  :directives @(:directives ctrl)
                  :outcome (:outcome so)))))

(defn agents
  "The launched agents, in launch order: a vector of {:thread-id :status}."
  [harness]
  (mapv (fn [tid] {:thread-id tid :status (:status (sched/observe (:scheduler harness) tid))})
        @(:order harness)))

(defn wait-all
  "Block until every launched agent reaches a terminal status. Returns one
  observe map per agent, in launch order. Times out to ::timeout like the
  underlying scheduler (default 300000 ms)."
  [harness]
  (let [r (sched/wait-all (:scheduler harness))]
    (if (= ::sched/timeout r)
      ::sched/timeout
      (mapv (fn [tid] (observe harness tid)) @(:order harness)))))

(defn shutdown!
  "Cancel every launched agent (stop at the next goal check), soft-cancel any
  in-flight tool calls on the shared rap server, and close the harness's own
  scheduler. Does not close the :srv/:store/:rt/:mem the caller supplied."
  [harness]
  (doseq [[tid c] @(:agents harness)]
    (reset! (:goal-ref c) (constantly true))
    (try (rap/close (:srv (:opts harness)) tid) (catch Exception _ nil)))
  (sched/close! (:scheduler harness))
  (reset! (:agents harness) {})
  (reset! (:order harness) [])
  :shutdown)
