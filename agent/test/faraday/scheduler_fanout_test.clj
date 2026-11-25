;; faraday-agent scheduler fanout test: an orchestrator agent spawns N
;; subagents via the {:spawn {:children [{:task ..}]}} action; each child runs
;; its own sandbox exec in parallel; the scheduler parks the parent as data
;; while the children work and resumes it (folded :subagent-results) only when
;; every child is terminal. Proves the superagent/subagent fanout shape over the
;; same yieldable-loop state machine, with fakes for pool + microvm (no KVM).
;; Run: cd faraday-agent && jolt run test/faraday/scheduler_fanout_test.clj
(ns jolt.test.scheduler-fanout
  (:require [faraday.agent.scheduler :as sched]
            [faraday.thread :as thread]
            [faraday.rap :as rap]
            [faraday.fanout :as fanout]
            [faraday.ledger :as ledger]
            [faraday.memo :as memo]
            [faraday.memory :as memory]
            [faraday.pool :as pool]
            [jolt.microvm :as microvm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- fakes -------------------------------------------------------------------
(def acquires (atom []))
(def hibernates (atom []))
(def execs (atom []))
(def next-id (atom 0))
(def busy (atom 0))
(def peak-busy (atom 0))

(defn fake-acquire [_ request]
  (let [id (str "h" (swap! next-id inc))]
    (swap! acquires conj request)
    (swap! busy inc)
    (swap! peak-busy max @busy)
    {:id id :vm {:fake? true :vm id}}))

(defn fake-release [_ handle] :released)

(defn fake-hibernate [_ handle]
  (swap! hibernates conj (:id handle))
  (swap! busy dec)
  (str "snap_" (:id handle)))

(defn fake-restore [_ snap]
  (swap! busy inc)
  {:id (subs snap 5) :vm {:fake? true :vm (subs snap 5)}})

(defn fake-exec [_vm cmd & {:keys [timeout-ms]}]
  (swap! execs conj cmd)
  (Thread/sleep 100)
  {:exit 0 :stdout "ok" :stderr ""})

;; -- the run -----------------------------------------------------------------
(with-redefs [pool/acquire fake-acquire
              pool/release fake-release
              pool/hibernate fake-hibernate
              pool/restore fake-restore
              microvm/exec fake-exec]
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/fanout-" (System/nanoTime))
        ledg (ledger/new-ledger {:path (str tmp "/ledger.db")})
        memo (memo/new-memo {:path (str tmp "/memo")})
        memy (memory/new-memory {:path (str tmp "/mem.db") :ledger ledg :memo memo})
        rt (fanout/new-runtime {:ledger ledg :memo memo})
        store (thread/new-store {:pool ::pool :repo-root (str tmp "/repos")})
        srv (rap/new-server {:fanout rt :run-hook (thread/rap-hook store)})
        sch (sched/new-scheduler {:srv srv :store store :rt rt :mem memy})
        child-manifest {:v 1 :loop-id "child" :name "subagent"
                        :agent {:kind "script"
                                :script [{:tool-call {:name "run"
                                                      :args {:argv ["echo" "child-work"]
                                                             :repo {:mode "direct" :host-path "/srv/repos/demo"}}}}
                                         {:done {:result :child-done}}]}}
        parent-manifest {:v 1 :loop-id "parent" :name "orchestrator"
                         :agent {:kind "script"
                                 :script [{:spawn {:children [{:task "sub-1" :manifest child-manifest}
                                                              {:task "sub-2" :manifest child-manifest}
                                                              {:task "sub-3" :manifest child-manifest}]}}
                                          {:done {:result :collected}}]}}
        parent-tids (sched/launch! sch {:manifest parent-manifest :n 1})
        parent-tid (first parent-tids)]
    (check "one parent launched" 1 (count parent-tids))
    (let [st (sched/stats sch)]
      (check "4 threads in flight (parent + 3 children)" 4 (:threads st))
      (check "3 parked on tool results after launch" 3 (:parked st))
      (check "1 awaiting children after launch" 1 (:awaiting st)))
    (let [outcomes (sched/wait-all sch)]
      (check "wait-all returns the whole tree (4)" 4 (count outcomes))
      (check "every thread terminal" true (every? #(contains? #{:done :max-turns :error :cancelled} (:status %)) outcomes))
      (let [parent (first (filter #(= parent-tid (:thread-id %)) outcomes))
            kids (filter #(= parent-tid (:parent-id %)) outcomes)]
        (check "parent done with :collected" :collected (get-in parent [:outcome :result :result]))
        (check "3 children collected" 3 (count kids))
        (check "every child done with :child-done" true
               (every? #(= :child-done (get-in % [:outcome :result :result])) kids))
        (check "parent observes its 3 children" 3 (count (:children parent)))))
    (check "3 sandboxes live at peak (parallel workers)" 3 @peak-busy)
    (check "3 execs ran (one per child)" 3 (count @execs))
    (check "3 sandboxes hibernated (children)" 3 (count @hibernates))
    (let [st (sched/stats sch)]
      (check "scheduler idle after the tree finished" 0 (:active st))
      (check "3 snapshots held (children only — parent never exec'd)" 3 (:snapshots st)))
    ;; ledger tree: every child's history opens with a spawn whose parent-id is
    ;; the orchestrator's thread-id.
    (let [kids (get-in @(:state sch) [:children parent-tid])
          kid-histories (mapv #(ledger/history ledg %) kids)]
      (check "3 child histories" 3 (count kid-histories))
      (check "each child history opens with spawn + has report + yield" true
             (every? (fn [h]
                       (and (= "spawn" (:kind (first h)))
                            (some #(= "report" (:kind %)) h)
                            (some #(= "yield" (:kind %)) h)))
                     kid-histories)))))
(println "ALL FANOUT TESTS PASSED")
