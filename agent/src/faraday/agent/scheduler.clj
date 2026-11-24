;; faraday-agent scheduler: bounded dispatcher over yieldable loops.
;; Replaces harness/launch!'s one-future-per-agent with one state machine:
;; launch! steps each agent once (it invokes its first tool async and parks as
;; data), and a rap on-result callback resumes exactly that thread when its
;; result lands. No live future holds a waiting agent; the warm VM pool is the
;; only concurrency bound. This is infinity's model: 1000s of agent threads,
;; each yielding while it waits, multiplexed over a bounded pool.
(ns faraday.agent.scheduler
  "Bounded dispatcher over faraday.agent.yield loops.

  launch! mints a thread via fanout/spawn, starts a yieldable loop, and steps
  it once; a waiting agent is data (checkpointed, parked), not a live future.
  A rap on-result callback resumes that thread when its tool result lands. The
  warm VM pool bounds actual sandbox concurrency, so the scheduler can drive
  thousands of threads with zero per-agent processes."
  (:require [faraday.agent.yield :as y]
            [faraday.rap :as rap]
            [faraday.fanout :as fanout]
            [faraday.thread :as thread]))

(defrecord Scheduler [opts state lock])

(def ^:private terminal-statuses #{:done :max-turns :error :cancelled})

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.scheduler/error true))))

(defn- terminal? [status]
  (contains? terminal-statuses status))

(defn- deps [sched]
  (select-keys (:opts sched) [:srv :store :rt :mem]))

(defn- bump-active! [sched dir]
  (swap! (:state sched)
         (fn [s] (let [a (+ (or (:active s) 0) dir)]
                   (-> s (assoc :active a) (update :peak-active max a))))))

(declare deliver!)

(defn- register-result! [sched thread-id call-id]
  (rap/on-result (:srv (:opts sched)) call-id
                 (fn [frame] (deliver! sched thread-id frame))))

(defn new-scheduler
  "Open a scheduler over opts: :srv (faraday-rap server whose run-hook is
  thread-keyed, e.g. (thread/rap-hook store)), :store (faraday-thread store),
  :rt (faraday-fanout runtime), and optional :mem (faraday-memory) for durable
  checkpoints. Returns an opaque scheduler handle."
  [opts]
  (let [srv (:srv opts) store (:store opts) rt (:rt opts)]
    (when-not srv (fail! "new-scheduler requires a :srv" {:opts opts}))
    (when-not store (fail! "new-scheduler requires a :store" {:opts opts}))
    (when-not rt (fail! "new-scheduler requires a :rt" {:opts opts}))
    (->Scheduler opts
                 (atom {:threads {} :order [] :remaining 0 :active 0 :peak-active 0
                        :done-promise (promise)})
                 (Object.))))

(defn- mark-terminal! [sched]
  (let [s (swap! (:state sched) update :remaining dec)]
    (when (zero? (:remaining s))
      (deliver (:done-promise s) true))))

(defn deliver!
  "Deliver a tool result frame to the thread that owns its call-id. Resumes the
  parked loop, steps it, and re-arms the next on-result if it yields again.
  Safe to call from rap's result-callback futures."
  [sched thread-id frame]
  (let [entry (get-in @(:state sched) [:threads thread-id])]
    (when entry
      (locking (:lock entry)
        (bump-active! sched 1)
        (try
          (let [l @(:loop entry)
                cid (:call-id frame)]
            (when (and (= :yielded (:status l)) (= cid (:pending-call-id l)))
              (let [l2 (y/resume! (deps sched) l frame)]
                (reset! (:loop entry) l2)
                (if (terminal? (:status l2))
                  (mark-terminal! sched)
                  (register-result! sched thread-id (:pending-call-id l2))))))
          (catch Exception e
            (reset! (:loop entry)
                    {:status :error :thread-id thread-id
                     :outcome {:status :error
                               :error (or (ex-message e) "resume failed")}})
            (mark-terminal! sched))
          (finally (bump-active! sched -1)))))))

(defn launch!
  "Launch N yieldable loops of manifest. Each thread is minted via
  fanout/spawn (so the ledger records it), stepped once, and parked. Returns
  the minted thread-ids in order.

  opts: :manifest (required), :n (default 1), :task, :parent-id, :spec (spawn
  payload, default the manifest), :step-fn (compiled faraday.agent.mw step) or
  :step-fns (vector of n steps, one per thread), :goal-fn (predicate over ctx)
  or :goal-fns (vector of n predicates, one per thread, overriding :goal-fn)."
  [sched opts]
  (let [m (:manifest opts)
        n (or (:n opts) 1)
        task (:task opts)
        parent-id (:parent-id opts)
        spec (or (:spec opts) m)
        rt (:rt (:opts sched))]
    (when-not m (fail! "launch! requires a :manifest" {:opts opts}))
    (swap! (:state sched) update :remaining (fn [r] (+ r n)))
    (mapv (fn [i]
            (let [msg (fanout/spawn rt parent-id spec nil)
                  tid (:thread-id msg)
                  goal-fn (cond
                            (contains? opts :goal-fns) (nth (:goal-fns opts) i)
                            (contains? opts :goal-fn) (:goal-fn opts)
                            :else ::absent)
                  step-fn (if (contains? opts :step-fns)
                            (nth (:step-fns opts) i)
                            (:step-fn opts))
                  l (y/start! (cond-> {:manifest m :thread-id tid :task task}
                                (some? step-fn) (assoc :step-fn step-fn)
                                (not= ::absent goal-fn) (assoc :goal-fn goal-fn)))
                  l2 (y/step! (deps sched) l)]
              (swap! (:state sched)
                     (fn [s] (-> s
                                 (update :order conj tid)
                                 (assoc-in [:threads tid] {:loop (atom l2) :lock (Object.)}))))
              (if (terminal? (:status l2))
                (mark-terminal! sched)
                (register-result! sched tid (:pending-call-id l2)))
              tid))
          (range n))))

(defn observe
  "One thread's current loop state: {:thread-id :status :outcome :turns
  :snapshot-ref}."
  [sched thread-id]
  (let [l @(get-in @(:state sched) [:threads thread-id :loop])]
    {:thread-id thread-id
     :status (:status l)
     :outcome (:outcome l)
     :turns (:turns l)
     :snapshot-ref (:snapshot-ref l)}))

(defn stats
  "{:threads n :parked n :done n :snapshots n :active n :peak-active n}.
  :peak-active is the max concurrent resume/deliver work, i.e. the scheduler's
  live-agent bound (the pool bounds sandboxes)."
  [sched]
  (let [s @(:state sched)
        loops (map #(-> % :loop deref) (vals (:threads s)))]
    {:threads (count loops)
     :parked (count (filter #(= :yielded (:status %)) loops))
     :done (count (filter #(terminal? (:status %)) loops))
     :snapshots (count (keep :snapshot-ref loops))
     :active (:active s)
     :peak-active (:peak-active s)}))

(defn agents
  "The launched threads: a vector of {:thread-id :status}."
  [sched]
  (mapv (fn [tid] {:thread-id tid :status (:status (observe sched tid))})
        (:order @(:state sched))))

(defn wait-all
  "Block until every launched thread is terminal. Returns one observe map per
  thread, in launch order. Times out to ::timeout after :timeout-ms (default
  300000)."
  [sched]
  (let [r (deref (:done-promise @(:state sched))
                 (or (:timeout-ms (:opts sched)) 300000) ::timeout)]
    (if (= ::timeout r)
      ::timeout
      (mapv #(observe sched %) (:order @(:state sched))))))

(defn close!
  "Forget all threads and reset the scheduler. Does not close the rap server,
  thread store or fanout it was given."
  [sched]
  (reset! (:state sched)
          {:threads {} :order [] :remaining 0 :active 0 :peak-active 0
           :done-promise (promise)})
  nil)
