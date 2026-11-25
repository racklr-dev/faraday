;; faraday-agent scheduler: bounded dispatcher over yieldable loops.
;; Replaces harness/launch!'s one-future-per-agent with one state machine:
;; launch! steps each agent once (it invokes its first tool async and parks as
;; data), and a rap on-result callback resumes exactly that thread when its
;; result lands. No live future holds a waiting agent; the warm VM pool is the
;; only concurrency bound. This is infinity's model: 1000s of agent threads,
;; each yielding while it waits, multiplexed over a bounded pool.
;;
;; Subagent fanout: an agent's step-fn may return {:spawn {:children [{:task ..}]}};
;; the scheduler mints each child as a NEW thread under the parent (fanout
;; spawn with parent-id), and when every child reaches a terminal status the
;; parent is resumed with the children's outcomes folded into its transcript
;; (y/resume-children!). A parent parked awaiting children is data, not a live
;; future, exactly like a parent parked awaiting a tool result.
(ns faraday.agent.scheduler
  "Bounded dispatcher over faraday.agent.yield loops.

  launch! mints a thread via fanout/spawn, starts a yieldable loop, and steps
  it once; a waiting agent is data (checkpointed, parked), not a live future.
  A rap on-result callback resumes that thread when its tool result lands. The
  warm VM pool bounds actual sandbox concurrency, so the scheduler can drive
  thousands of threads with zero per-agent processes.

  Subagent fanout is the same machinery one level up: {:spawn {:children
  [{:task ..}]}} mints child threads under the parent, and the scheduler
  resumes the parent when all its children are terminal, with their outcomes
  folded into the parent transcript as :subagent-results."
  (:require [faraday.agent.yield :as y]
            [faraday.agent.loop :as loop]
            [faraday.rap :as rap]
            [faraday.fanout :as fanout]
            [faraday.thread :as thread]))

(defrecord Scheduler [opts state lock])

(def ^:private terminal-statuses #{:done :max-turns :error :cancelled})

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.scheduler/error true))))

(defn- terminal? [status]
  (contains? terminal-statuses status))

(declare deliver! observe mark-terminal! notify-parent!
         resume-if-all-children-terminal! spawn-children! launch-thread!)

(defn- deps [sched]
  (assoc (select-keys (:opts sched) [:srv :store :rt :mem])
         :spawn! (fn [parent-loop children]
                   (spawn-children! sched parent-loop children))))

(defn- bump-active! [sched dir]
  (swap! (:state sched)
         (fn [s] (let [a (+ (or (:active s) 0) dir)]
                   (-> s (assoc :active a) (update :peak-active max a))))))

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
                        :children {} :parent {}
                        :done-promise (promise)})
                 (Object.))))

(defn- mark-terminal! [sched tid]
  (let [flag (some-> (get-in @(:state sched) [:threads tid]) :terminal?)]
    (when (and flag (not @flag))
      (reset! flag true)
      (swap! (:state sched) update :remaining dec)
      (let [s @(:state sched)]
        (when (zero? (:remaining s))
          (deliver (:done-promise s) true)))
      (notify-parent! sched tid))))

(defn- notify-parent! [sched child-tid]
  (when-let [parent-id (get-in @(:state sched) [:parent child-tid])]
    (resume-if-all-children-terminal! sched parent-id)))

(defn- resume-children-or-error [sched parent-tid l child-outs]
  (try
    (y/resume-children! (deps sched) l child-outs)
    (catch Exception e
      {:status :error :thread-id parent-tid
       :outcome {:status :error
                 :error (or (ex-message e) "resume-children failed")}})))

(defn- resume-if-all-children-terminal! [sched parent-tid]
  (when-let [entry (get-in @(:state sched) [:threads parent-tid])]
    (let [retry? (atom false)]
      (locking (:lock entry)
        (let [l @(:loop entry)]
          (when (and (= :awaiting-children (:status l))
                     (not @(:resuming? entry)))
            (let [child-outs (mapv #(observe sched %) (:children l))]
              (when (and (seq child-outs)
                         (every? #(terminal? (:status %)) child-outs))
                (reset! (:resuming? entry) true)
                (bump-active! sched 1)
                (let [l2 (resume-children-or-error sched parent-tid l child-outs)]
                  (reset! (:loop entry) l2)
                  (reset! (:resuming? entry) false)
                  (bump-active! sched -1)
                  (cond
                    (terminal? (:status l2)) (mark-terminal! sched parent-tid)
                    (= :yielded (:status l2)) (register-result! sched parent-tid (:pending-call-id l2))
                    :else (reset! retry? true))))))))
      (when @retry?
        (resume-if-all-children-terminal! sched parent-tid)))))

(defn- spawn-children! [sched parent-loop children]
  (let [parent-tid (:thread-id parent-loop)
        pm (:manifest parent-loop)
        pstep (:step-fn parent-loop)
        pgoal (:goal-fn parent-loop)]
    (mapv (fn [child]
            (let [manifest (or (:manifest child) pm)
                  ;; A child with its own (or an inherited :script) manifest gets
                  ;; a FRESH step-fn closure: scripted-agent carries mutable
                  ;; position, so it must never be shared across threads. A
                  ;; :model child inherits the parent's compiled mw chain, which
                  ;; is stateless and cannot be rebuilt by manifest-agent.
                  step-fn (cond
                            (contains? child :step-fn) (:step-fn child)
                            (= "script" (some-> manifest :agent :kind name))
                            (loop/manifest-agent manifest)
                            :else pstep)
                  goal-fn (cond
                            (contains? child :goal-fn) (:goal-fn child)
                            (contains? child :goal) (loop/goal-fn (:goal child))
                            (= "script" (some-> manifest :agent :kind name))
                            ::absent
                            :else pgoal)]
              (launch-thread!
               sched
               {:manifest manifest
                :task (:task child)
                :spec (or (:spec child) manifest)
                :step-fn step-fn
                :goal-fn goal-fn
                :parent-id parent-tid})))
          children)))

(defn- launch-thread! [sched {:keys [manifest task parent-id spec step-fn goal-fn]}]
  (let [rt (:rt (:opts sched))
        msg (fanout/spawn rt parent-id (or spec manifest) nil)
        tid (:thread-id msg)
        l (y/start! (cond-> {:manifest manifest :thread-id tid :task task}
                      (some? step-fn) (assoc :step-fn step-fn)
                      (not= ::absent goal-fn) (assoc :goal-fn goal-fn)))
        l2 (y/step! (deps sched) l)]
    (swap! (:state sched)
           (fn [s]
             (-> s
                 (update :remaining inc)
                 (update :order conj tid)
                 (assoc-in [:threads tid]
                           {:loop (atom l2) :lock (Object.)
                            :terminal? (atom false) :resuming? (atom false)})
                 (cond-> parent-id
                   (-> (update-in [:children parent-id] (fnil conj []) tid)
                       (assoc-in [:parent tid] parent-id))))))
    (cond
      (terminal? (:status l2)) (mark-terminal! sched tid)
      (= :awaiting-children (:status l2)) (resume-if-all-children-terminal! sched tid)
      :else (register-result! sched tid (:pending-call-id l2)))
    tid))

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
                  (mark-terminal! sched thread-id)
                  (register-result! sched thread-id (:pending-call-id l2))))))
          (catch Exception e
            (reset! (:loop entry)
                    {:status :error :thread-id thread-id
                     :outcome {:status :error
                               :error (or (ex-message e) "resume failed")}})
            (mark-terminal! sched thread-id))
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
        spec (or (:spec opts) m)]
    (when-not m (fail! "launch! requires a :manifest" {:opts opts}))
    (mapv (fn [i]
            (let [goal-fn (cond
                            (contains? opts :goal-fns) (nth (:goal-fns opts) i)
                            (contains? opts :goal-fn) (:goal-fn opts)
                            :else ::absent)
                  step-fn (if (contains? opts :step-fns)
                            (nth (:step-fns opts) i)
                            (:step-fn opts))]
              (launch-thread! sched {:manifest m :task task :parent-id parent-id
                                     :spec spec :step-fn step-fn :goal-fn goal-fn})))
          (range n))))

(defn observe
  "One thread's current loop state: {:thread-id :status :outcome :turns
  :snapshot-ref}, plus :children (vector of child thread-ids) and :parent-id
  when the thread is part of a fanout tree."
  [sched thread-id]
  (let [st @(:state sched)
        l @(get-in st [:threads thread-id :loop])]
    (merge {:thread-id thread-id
            :status (:status l)
            :outcome (:outcome l)
            :turns (:turns l)
            :snapshot-ref (:snapshot-ref l)}
           (when-let [kids (get-in st [:children thread-id])]
             {:children kids})
           (when-let [p (get-in st [:parent thread-id])]
             {:parent-id p}))))

(defn stats
  "{:threads n :parked n :awaiting n :done n :snapshots n :active n
  :peak-active n}. :parked are loops waiting on a tool result, :awaiting are
  loops parked waiting on their children. :peak-active is the max concurrent
  resume/deliver work, i.e. the scheduler's live-agent bound (the pool bounds
  sandboxes)."
  [sched]
  (let [s @(:state sched)
        loops (map #(-> % :loop deref) (vals (:threads s)))]
    {:threads (count loops)
     :parked (count (filter #(= :yielded (:status %)) loops))
     :awaiting (count (filter #(= :awaiting-children (:status %)) loops))
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
  "Block until every launched thread (the whole fanout tree) is terminal.
  Returns one observe map per thread, in launch order. Times out to ::timeout
  after :timeout-ms (default 300000)."
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
           :children {} :parent {} :done-promise (promise)})
  nil)
