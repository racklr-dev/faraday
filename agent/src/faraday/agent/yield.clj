;; faraday-agent yield: the yieldable turn loop.
;; Where faraday.agent/run! blocks a live future on each tool result, this
;; loop yields: it invokes the tool asynchronously, checkpoints the
;; continuation to memory (durable, hibernate-to-data), and parks - no live
;; process holds the agent while it waits. resume! steps again when the result
;; frame lands. On a terminal state the idle sandbox is hibernated to a
;; snapshot and a yield Thread Message records the rest state.
(ns faraday.agent.yield
  "Yieldable turn loop for a jolt-based control plane.

  agent/run! blocks a live future on each tool result. This loop instead
  yields: it invokes the tool asynchronously, checkpoints the continuation to
  memory, and parks, so an agent waiting on a tool costs no live process. When
  the result frame lands, resume! appends it, reports to the fanout, and steps
  again. On a terminal state the thread's now-idle sandbox is hibernated to a
  snapshot and a yield Thread Message records the rest state (state-hash +
  snapshot-ref), so the thread can be resumed later with no live VM."
  (:require [faraday.agent :as agent]
            [faraday.agent.loop :as loop]
            [faraday.rap :as rap]
            [faraday.thread :as thread]
            [faraday.fanout :as fanout]
            [faraday.memory :as memory]
            [faraday.contracts :as contracts]))

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.yield/error true))))

(defn- state-hash
  "SHA-256 hex over the pr-str of the resumable state: content-addressed and
  stable across runs (unlike clojure.core/hash)."
  [x]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bytes (.digest md (.getBytes (pr-str x) "UTF-8"))]
    (apply str (map (fn [b] (format "%02x" (bit-and 0xff b))) bytes))))

(defn resumable
  "The subset of the loop that must survive a yield: everything except the
  manifest and its derived fns, which the scheduler re-attaches on resume."
  [l]
  (select-keys l [:thread-id :task :turns :transcript :pending-call-id
                  :pending-name :snapshot-ref :max-turns :timeout-ms :children]))

(defn checkpoint!
  "Durably checkpoint the loop's resumable state under its thread-id (the
  hibernate-to-data half of a yield). No-op when :mem is nil."
  [mem l]
  (when mem
    (memory/checkpoint! mem (:thread-id l) (resumable l))))

(defn restore-continuation
  "The checkpointed resumable state for thread-id, merged over fallback."
  [mem thread-id fallback]
  (if mem
    (merge fallback (memory/restore mem thread-id))
    fallback))

(defn- hibernate-sandbox!
  "l is terminal and its sandbox is idle: suspend the sandbox to a snapshot and
  emit the yield (hibernate-to-data) Thread Message. Returns l with
  :snapshot-ref set when a snapshot was taken."
  [l deps]
  (let [store (:store deps) rt (:rt deps)]
    (if store
      (let [snap (thread/yield! store (:thread-id l))]
        (when snap
          (fanout/yield rt (:thread-id l) (state-hash (resumable l)) snap))
        (assoc l :snapshot-ref snap))
      l)))

(defn start!
  "Begin a yieldable loop from a manifest. opts: :manifest (required), plus
  optional :task :thread-id :max-turns :timeout-ms :goal-fn overrides.
  Returns the loop map."
  [opts]
  (let [m (:manifest opts)]
    (when-not m (fail! "start! requires a :manifest" {:opts opts}))
    (let [thread-id (or (:thread-id opts) (:thread-id m)
                        (str "thr_" (java.util.UUID/randomUUID)))
          task (or (:task opts) (:task m))
          goal-fn (if (contains? opts :goal-fn)
                    (:goal-fn opts)
                    (loop/goal-fn (:goal m)))]
      {:thread-id thread-id
       :manifest m
       :step-fn (or (:step-fn opts) (loop/manifest-agent m))
       :goal-fn goal-fn
       :task task
       :turns 0
       :transcript (if task [{:role :user :content task}] [])
       :max-turns (or (:max-turns opts) (:max-turns m) 32)
       :timeout-ms (or (:timeout-ms opts) (:timeout-ms m) 120000)
       :status :launched
       :pending-call-id nil
       :pending-name nil
       :snapshot-ref nil
       :outcome nil})))

(defn step!
  "Advance loop by one action over deps {:srv :store :rt :mem}. When the agent
  wants a tool-call, invoke it async, checkpoint, and park (returns loop with
  :status :yielded). Otherwise return loop with a terminal :status. On a
  terminal the idle sandbox is hibernated."
  [deps l]
  (let [srv (:srv deps) mem (:mem deps)]
    (if (and (:goal-fn l) ((:goal-fn l) l))
      (-> (assoc l :status :done
                   :outcome {:status :done :stop-reason :goal :result {:goal-met true}
                             :met true :transcript (:transcript l) :turns (:turns l)})
          (hibernate-sandbox! deps))
      (let [action (try ((:step-fn l) l) (catch Exception e {:step-error e}))]
        (cond
          (contains? action :done)
          (let [entry {:role :assistant :content (:done action)}
                transcript (conj (:transcript l) entry)]
            (-> (assoc l :transcript transcript :status :done
                         :outcome {:status :done :stop-reason :agent :result (:done action)
                                   :transcript transcript :turns (:turns l)})
                (hibernate-sandbox! deps)))

          (contains? action :tool-call)
          (if (>= (:turns l) (:max-turns l))
            (-> (assoc l :status :max-turns
                         :outcome {:status :max-turns :transcript (:transcript l)
                                   :turns (:turns l)})
                (hibernate-sandbox! deps))
            (let [call (:tool-call action)
                  call-entry {:role :tool-call :name (:name call) :args (:args call)
                              :display-as (:display-as call)}
                  cid (rap/invoke srv (assoc call :thread-id (:thread-id l)))
                  l2 (assoc l :transcript (conj (:transcript l) call-entry)
                              :turns (inc (:turns l))
                              :pending-call-id cid
                              :pending-name (:name call)
                              :status :yielded)]
              (checkpoint! mem l2)
              l2))

          (contains? action :spawn)
          (let [children (:children (:spawn action))]
            (if (and (vector? children) (seq children)
                     (every? #(and (map? %) (string? (:task %)) (seq (:task %))) children))
              (if (>= (:turns l) (:max-turns l))
                (-> (assoc l :status :max-turns
                             :outcome {:status :max-turns :transcript (:transcript l)
                                       :turns (:turns l)})
                    (hibernate-sandbox! deps))
                (let [spawn-fn (:spawn! deps)]
                  (if-not (ifn? spawn-fn)
                    (-> (assoc l :status :error
                                 :outcome {:status :error
                                           :error ":spawn action requires deps :spawn! (the scheduler seam)"
                                           :transcript (:transcript l) :turns (:turns l)})
                        (hibernate-sandbox! deps))
                    (let [entry {:role :spawn :children (mapv #(select-keys % [:task]) children)}
                          child-tids (spawn-fn l children)
                          l2 (assoc l :transcript (conj (:transcript l) entry)
                                      :turns (inc (:turns l))
                                      :children (vec child-tids)
                                      :status :awaiting-children)]
                      (checkpoint! mem l2)
                      l2))))
              (-> (assoc l :status :error
                           :outcome {:status :error :error ":spawn requires a non-empty :children vector of {:task ..} maps"
                                     :transcript (:transcript l) :turns (:turns l)})
                  (hibernate-sandbox! deps))))

          (contains? action :step-error)
          (-> (assoc l :status :error
                       :outcome {:status :error
                                 :error (or (ex-message (:step-error action)) "step failed")
                                 :transcript (:transcript l) :turns (:turns l)})
              (hibernate-sandbox! deps))

          :else
          (-> (assoc l :status :error
                       :outcome {:status :error :error (str "invalid action: " (pr-str action))
                                 :transcript (:transcript l) :turns (:turns l)})
              (hibernate-sandbox! deps)))))))

(defn resume!
  "Resume loop after its pending tool result frame arrived. Appends the result
  entry, reports to the fanout, clears the pending call, and steps again.
  Returns the next loop (possibly :yielded again or terminal)."
  [deps l frame]
  (let [rt (:rt deps)
        res (try (contracts/decode! :tool-result frame) (catch Exception _ nil))
        status (if res (:status res) "error")
        segs (if res (:segments res) [])
        err (when res (:error res))
        exit-code (when res (:exit-code res))]
    (fanout/report rt (:thread-id l) status
                   {:exit-code exit-code :call-id (:pending-call-id l)} segs)
    (let [entry (merge {:role :tool-result :call-id (:pending-call-id l)
                        :name (:pending-name l) :status status :segments segs}
                       (when err {:error err})
                       (when exit-code {:exit-code exit-code})
                       {:frame frame})
          l2 (assoc l :transcript (conj (:transcript l) entry)
                      :pending-call-id nil :pending-name nil)]
      (step! deps l2))))

(defn resume-children!
  "Resume a parent parked at :awaiting-children: fold its children's terminal
  outcomes into the transcript and step again, so the parent agent reads its
  subagent results and continues (or finishes)."
  [deps l children-outcomes]
  (let [entry {:role :subagent-results :children children-outcomes}
        l2 (assoc l :transcript (conj (:transcript l) entry)
                    :children []
                    :pending-call-id nil :pending-name nil)]
    (step! deps l2)))
