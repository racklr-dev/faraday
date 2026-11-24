;; faraday-agent scheduler test: yieldable loops over a thread-keyed rap server,
;; using fakes for faraday.pool and jolt.microvm (no KVM/libkrun). Proves the
;; infinity properties: one sandbox per thread reused across its tool calls,
;; agents parked as data while waiting (no per-agent future), resume on result,
;; and hibernate-to-snapshot on completion.
;; Run: cd faraday-agent && jolt run test/faraday/scheduler_test.clj
(ns jolt.test.scheduler
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
  (let [tmp (str (System/getProperty "java.io.tmpdir") "/sch-" (System/nanoTime))
        ledg (ledger/new-ledger {:path (str tmp "/ledger.db")})
        memo (memo/new-memo {:path (str tmp "/memo")})
        memy (memory/new-memory {:path (str tmp "/mem.db") :ledger ledg :memo memo})
        rt (fanout/new-runtime {:ledger ledg :memo memo})
        store (thread/new-store {:pool ::pool :repo-root (str tmp "/repos")})
        srv (rap/new-server {:fanout rt :run-hook (thread/rap-hook store)})
        sch (sched/new-scheduler {:srv srv :store store :rt rt :mem memy})
        manifest {:v 1 :loop-id "sch" :name "scheduler-test"
                  :agent {:kind "script"
                          :script [{:tool-call {:name "run" :args {:argv ["echo" "one"]
                                                                   :repo {:mode "direct" :host-path "/srv/repos/demo"}}}}
                                   {:tool-call {:name "run" :args {:argv ["echo" "two"]
                                                                   :repo {:mode "direct" :host-path "/srv/repos/demo"}}}}
                                   {:done {:result :complete}}]}}
        tids (sched/launch! sch {:manifest manifest :n 25})]
    (check "25 threads launched" 25 (count tids))
    (let [st (sched/stats sch)]
      (check "all parked after launch (data, not live)" 25 (:parked st))
      (check "none done after launch" 0 (:done st)))
    (let [outcomes (sched/wait-all sch)]
      (check "wait-all returns 25" 25 (count outcomes))
      (check "all 25 threads done" 25 (count (filter #(= :done (:status %)) outcomes)))
      (check "every result :complete" true
             (every? #(= :complete (get-in % [:outcome :result :result])) outcomes))
      (check "one acquire per thread (sandbox reused across 2 tool calls)"
             25 (count @acquires))
      (check "two execs per thread" 50 (count @execs))
      (check "repo mounted on every acquire" true
             (every? #(= "/srv/repos/demo" (get-in % [:mounts 0 :host-path])) @acquires))
      (check "25 hibernations on completion" 25 (count @hibernates))
      (let [st (sched/stats sch)]
        (check "25 snapshots held after completion" 25 (:snapshots st))
        (check "scheduler idle (no live work)" 0 (:active st)))
      (check "first thread's parked continuation is durable" true
             (some? (memory/restore memy (first tids))))
      (let [kinds (mapv :kind (ledger/history ledg (first tids)))]
        (check "history opens with spawn" "spawn" (first kinds))
        (check "history has 2 reports (one per tool result)" 2
               (count (filter #(= "report" %) kinds)))
        (check "history has 1 yield (completion hibernate)" 1
               (count (filter #(= "yield" %) kinds))))
      (check "peak concurrent sandboxes was 25 (pool bounds this in prod)" 25 @peak-busy))))
(println "ALL SCHEDULER TESTS PASSED")
