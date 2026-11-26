;; faraday-memory test: two-tier facts, FTS5 over ledger+memo, durable
;; checkpoint/resume, and the post-session extract! orchestrator.
;; Run: cd memory && jolt run test/faraday/memory_test.clj
(ns jolt.test.memory
  (:require [faraday.memory :as memory]
            [faraday.ledger :as ledger]
            [faraday.memo :as memo]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/faraday-memory-test-" (System/nanoTime)))

(defn msg [thread-id msg-id & {:as overrides}]
  (merge {:v 1 :thread-id thread-id :parent-id nil :msg-id msg-id
          :kind "spawn" :ts "2026-11-22T07:24:19.907Z" :payload {}}
         overrides))

(defn with-mem [f]
  (let [base (tmp-dir)
        l (ledger/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        mem (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})]
    (try
      (f mem l m base)
      (finally (memory/close! mem) (ledger/close! l) (memo/close! m)))))

;; -- two-tier facts: salient inline, demote to breadcrumb index ---------------
(with-mem
  (fn [mem _ _ _]
    (memory/note! mem "thr_1" :goal "port the narrow waist")
    (memory/note! mem "thr_1" :stack {:lang "clojure" :runtime "jolt"})
    (check "two salient facts inline"
           {:goal "port the narrow waist" :stack {:lang "clojure" :runtime "jolt"}}
           (memory/salient mem "thr_1"))
    (check "no breadcrumbs yet" {} (memory/breadcrumbs mem "thr_1"))

    (check "demote a salient fact" true (memory/demote! mem "thr_1" :stack))
    (check "salient shrinks to goal" {:goal "port the narrow waist"} (memory/salient mem "thr_1"))
    (check "breadcrumb index has stack" {:stack {:lang "clojure" :runtime "jolt"}}
           (memory/breadcrumbs mem "thr_1"))
    (check "demote again is false" false (memory/demote! mem "thr_1" :stack))

    (check "promote back to salient" true (memory/promote! mem "thr_1" :stack))
    (check "salient restored"
           {:goal "port the narrow waist" :stack {:lang "clojure" :runtime "jolt"}}
           (memory/salient mem "thr_1"))

    (check "facts reports tiers"
           {:goal {:value "port the narrow waist" :tier "salient"}
            :stack {:value {:lang "clojure" :runtime "jolt"} :tier "salient"}}
           (memory/facts mem "thr_1"))

    ;; upsert replaces in place
    (memory/note! mem "thr_1" :goal "port the narrow waist (done)")
    (check "note! upserts in place" {:goal "port the narrow waist (done)"}
           (select-keys (memory/salient mem "thr_1") [:goal]))))

;; -- FTS5 search over ledger + memo ------------------------------------------
(with-mem
  (fn [mem l m _]
    (let [mk (memo/put! m {:summary "the build failed at the link step"})
          _ (ledger/append! l (msg "thr_s" "msg_a" :kind "report"
                                   :payload {:status "error" :memo-key mk}))
          _ (ledger/append! l (msg "thr_s" "msg_b" :kind "system"
                                   :payload {:note "quasar calibration"}))
          n (memory/index-thread! mem "thr_s")]
      (check "indexed 2 ledger + 1 memo docs" 3 n)
      (check "search finds a ledger message" #{"msg_b"}
             (set (map :doc-id (memory/search mem "quasar"))))
      (check "search finds a memo value" #{mk}
             (set (map :doc-id (memory/search mem "link"))))
      (check "search respects source" #{"memo"}
             (set (map :source (memory/search mem "link")))))))

;; -- durable checkpoint / resume by thread-id --------------------------------
(with-mem
  (fn [mem l m base]
    (let [state {:task "go" :transcript [{:role :user :content "hi"}] :turns 3}]
      (memory/checkpoint! mem "thr_c" state)
      (check "restore round-trips" state (memory/restore mem "thr_c"))
      (check "list-threads names checkpointed threads" ["thr_c"] (memory/list-threads mem))
      (memory/checkpoint! mem "thr_c" (assoc state :turns 4))
      (check "checkpoint upserts" {:task "go" :transcript [{:role :user :content "hi"}] :turns 4}
             (memory/restore mem "thr_c"))
      (memory/close! mem)
      (let [mem2 (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})]
        (check "checkpoint durable across reopen"
               {:task "go" :transcript [{:role :user :content "hi"}] :turns 4}
               (memory/restore mem2 "thr_c"))
        (memory/close! mem2))
      (let [mem3 (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})]
        (check "drop-checkpoint! present" true (memory/drop-checkpoint! mem3 "thr_c"))
        (check "drop-checkpoint! absent" false (memory/drop-checkpoint! mem3 "thr_c"))
        (check "restore nil after drop" nil (memory/restore mem3 "thr_c"))
        (memory/close! mem3)))))

;; -- post-session orchestrator extracts learnings -----------------------------
(with-mem
  (fn [mem l _ _]
    (ledger/append! l (msg "thr_e" "e1" :kind "spawn" :payload {:task "build it"}))
    (ledger/append! l (msg "thr_e" "e2" :kind "report" :payload {:status "error" :result {:x 1}}))
    (ledger/append! l (msg "thr_e" "e3" :kind "report" :payload {:status "ok" :result {:y 2}}))
    (let [learnings (memory/extract! mem "thr_e")]
      (check "extract message-count" 3 (:message-count learnings))
      (check "extract last-report" "e3" (get-in learnings [:last-report :msg-id]))
      (check "extract kind-counts" {"spawn" 1 "report" 2} (:kind-counts learnings))
      (check "learnings stored salient" "e3"
             (get-in (memory/salient mem "thr_e") [:last-report :msg-id]))
      (check "extract! with custom extract fn"
             {:custom true}
             (memory/extract! mem "thr_e" :extract (fn [_] {:custom true})))
      (check "custom learning stored" true
             (get-in (memory/salient mem "thr_e") [:custom])))))

(println "ALL MEMORY TESTS PASSED")
