;; faraday-ledger real test: seq assignment, msg-id dedup, history order,
;; processed?/last-seq, cross-thread independence, and persistence.
;; Run: cd faraday-ledger && jolt run test/jolt/ledger_test.clj
(ns jolt.test.ledger
  (:require [faraday.ledger :as ledger]))

(def failures (atom 0))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (swap! failures inc)
        (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got))))))

(defn msg
  "A Thread Message map; extra keys are allowed per SPEC.md forward-compat."
  [thread-id msg-id & {:as overrides}]
  (merge {:v 1
          :thread-id thread-id
          :parent-id nil
          :msg-id msg-id
          :kind "spawn"
          :ts "2026-11-22T07:24:19.907Z"
          :payload {}}
         overrides))

(defn with-ledger [f]
  (let [path (str "/tmp/faraday-ledger-test-" (System/nanoTime) "/ledger.db")
        db (ledger/new-ledger {:path path})]
    (try
      (f db path)
      (finally (ledger/close! db)))))

;; --- API surface -------------------------------------------------------------

(check "new-ledger defined" true (fn? ledger/new-ledger))
(check "append! defined" true (fn? ledger/append!))
(check "history defined" true (fn? ledger/history))
(check "processed? defined" true (fn? ledger/processed?))
(check "last-seq defined" true (fn? ledger/last-seq))
(check "put-pointer! defined" true (fn? ledger/put-pointer!))
(check "pointer defined" true (fn? ledger/pointer))
(check "delete-pointer! defined" true (fn? ledger/delete-pointer!))
(check "pointers defined" true (fn? ledger/pointers))
(check "close! defined" true (fn? ledger/close!))

;; --- seq assignment + history + dedup ---------------------------------------

(with-ledger
  (fn [db _]
    (check "append first assigns seq 0"
           {:seq 0 :deduped? false}
           (ledger/append! db (msg "t1" "m1")))
    (check "append second assigns seq 1"
           {:seq 1 :deduped? false}
           (ledger/append! db (msg "t1" "m2")))
    (check "append third assigns seq 2"
           {:seq 2 :deduped? false}
           (ledger/append! db (msg "t1" "m3")))

    (check "last-seq is highest" 2 (ledger/last-seq db "t1"))
    (check "processed? true after append" true (ledger/processed? db "m2"))
    (check "processed? false before append" false (ledger/processed? db "nope"))

    ;; redelivery is an idempotent no-op, not an error
    (check "redelivery deduped, original seq"
           {:seq 1 :deduped? true}
           (ledger/append! db (msg "t1" "m2")))
    (check "last-seq unchanged after redelivery" 2 (ledger/last-seq db "t1"))

    (let [h (ledger/history db "t1")]
      (check "history length excludes dedup" 3 (count h))
      (check "history seqs in order" [0 1 2] (mapv :seq h))
      (check "history msg-ids in order" ["m1" "m2" "m3"] (mapv :msg-id h))
      (check "history keys are keywords" #{:v :thread-id :parent-id :msg-id :seq :kind :ts :payload} (set (keys (first h))))
      (check "history round-trips payload" {} (:payload (first h)))
      (check "history round-trips thread-id" "t1" (:thread-id (second h))))

    ;; the ledger overwrites any seq the caller supplied
    (check "input seq is overwritten by ledger-assigned seq"
           {:seq 3 :deduped? false}
           (ledger/append! db (msg "t1" "m4" :seq 99)))
    (check "overwritten seq persisted" 3 (:seq (last (ledger/history db "t1"))))))

;; --- per-thread independence ------------------------------------------------

(with-ledger
  (fn [db _]
    (ledger/append! db (msg "ta" "a1"))
    (ledger/append! db (msg "ta" "a2"))
    (ledger/append! db (msg "tb" "b1"))
    (check "thread a last-seq" 1 (ledger/last-seq db "ta"))
    (check "thread b last-seq starts fresh" 0 (ledger/last-seq db "tb"))
    (check "thread a history" ["a1" "a2"] (mapv :msg-id (ledger/history db "ta")))
    (check "thread b history" ["b1"] (mapv :msg-id (ledger/history db "tb")))
    (check "unknown thread last-seq is nil" nil (ledger/last-seq db "nope"))
    (check "unknown thread history is empty" [] (ledger/history db "nope"))))

;; --- persistence across close/reopen ----------------------------------------

(with-ledger
  (fn [db path]
    (ledger/append! db (msg "t1" "p1"))
    (ledger/append! db (msg "t1" "p2"))
    (ledger/close! db)
    (let [db2 (ledger/new-ledger {:path path})]
      (try
        (check "reopen: last-seq preserved" 1 (ledger/last-seq db2 "t1"))
        (check "reopen: processed? preserved" true (ledger/processed? db2 "p1"))
        (check "reopen: history preserved" ["p1" "p2"] (mapv :msg-id (ledger/history db2 "t1")))
        (check "reopen: append continues seq"
               {:seq 2 :deduped? false}
               (ledger/append! db2 (msg "t1" "p3")))
        (check "reopen: redelivery still dedups"
               {:seq 0 :deduped? true}
               (ledger/append! db2 (msg "t1" "p1")))
        (finally (ledger/close! db2))))))

;; --- durable named pointers ------------------------------------------------

(with-ledger
  (fn [db _]
    (check "pointer absent initially" nil (ledger/pointer db "loop/active"))
    (check "put-pointer! returns value" "k1" (ledger/put-pointer! db "loop/active" "k1"))
    (check "pointer reads back" "k1" (ledger/pointer db "loop/active"))
    (check "put-pointer! replaces in place" "k2" (ledger/put-pointer! db "loop/active" "k2"))
    (check "pointer re-pointed" "k2" (ledger/pointer db "loop/active"))
    (check "pointer holds EDN maps" {:k 1} (ledger/put-pointer! db "m" {:k 1}))
    (check "pointer EDN round-trip" {:k 1} (ledger/pointer db "m"))
    (check "all pointers" {"loop/active" "k2" "m" {:k 1}} (ledger/pointers db))
    (check "delete-pointer! present" true (ledger/delete-pointer! db "m"))
    (check "delete-pointer! absent" false (ledger/delete-pointer! db "m"))
    (check "pointer nil after delete" nil (ledger/pointer db "m"))
    (check "all pointers after delete" {"loop/active" "k2"} (ledger/pointers db))))

(with-ledger
  (fn [db path]
    (ledger/put-pointer! db "loop/active" "k1")
    (ledger/close! db)
    (let [db2 (ledger/new-ledger {:path path})]
      (try
        (check "pointer persists across reopen" "k1" (ledger/pointer db2 "loop/active"))
        (finally (ledger/close! db2))))))

;; --- SPEC.md §1 canonical example round-trip ---------------------------------

(with-ledger
  (fn [db _]
    ;; The canonical vector from faraday-contracts examples/thread-message.json,
    ;; in the ledger's keyword-keyed in-process form.
    (let [ex {:v 1
              :thread-id "thr_01J8VK2Q"
              :parent-id nil
              :msg-id "msg_01J8VK2Q"
              :seq 0
              :kind "spawn"
              :ts "2026-11-22T07:24:19.907Z"
              :payload {}}]
      (check "canonical example appends" {:seq 0 :deduped? false} (ledger/append! db ex))
      (let [got (first (ledger/history db "thr_01J8VK2Q"))]
        (check "canonical example v" 1 (:v got))
        (check "canonical example thread-id" "thr_01J8VK2Q" (:thread-id got))
        (check "canonical example parent-id null" nil (:parent-id got))
        (check "canonical example msg-id" "msg_01J8VK2Q" (:msg-id got))
        (check "canonical example seq" 0 (:seq got))
        (check "canonical example kind" "spawn" (:kind got))
        (check "canonical example ts" "2026-11-22T07:24:19.907Z" (:ts got))
        (check "canonical example payload" {} (:payload got))))))

;; --- validation ---------------------------------------------------------------

(with-ledger
  (fn [db _]
    (let [err (fn [thunk] (try (thunk) ::no-throw (catch Exception e (ex-message e))))]
      (check "missing thread-id rejected"
             "thread-id must be a non-empty string"
             (err #(ledger/append! db {:msg-id "x" :kind "spawn"})))
      (check "unknown version rejected"
             "unknown message version"
             (err #(ledger/append! db (msg "t" "m" :v 2))))
      (check "unknown kind rejected"
             "unknown message kind"
             (err #(ledger/append! db (msg "t" "m" :kind "bogus"))))
      (check "parent-id = thread-id rejected"
             "parent-id must not equal thread-id"
             (err #(ledger/append! db (msg "t" "m" :parent-id "t")))))))

;; --- new-ledger parent dir creation -------------------------------------------

(let [db (ledger/new-ledger {:path "/tmp/faraday-ledger-test-deep/a/b/ledger.db"})]
  (check "new-ledger creates parent dirs" true (fn? ledger/append!))
  (ledger/close! db))

;; --- concurrent appends are serialized (single writer) ----------------------
(with-ledger
  (fn [db _]
    (let [fs (doall (for [t (range 8)]
                      (future
                        (try
                          (dotimes [i 10]
                            (ledger/append! db (msg (str "ct" t) (str "cm" t "-" i))))
                          :ok
                          (catch Throwable e [:err (ex-message e)])))))
          results (mapv deref fs)]
      (check "all concurrent writers ok"
             [:ok :ok :ok :ok :ok :ok :ok :ok] results)
      (check "each concurrent writer appended 10"
             [10 10 10 10 10 10 10 10]
             (mapv (fn [t] (count (ledger/history db (str "ct" t)))) (range 8))))))

(if (zero? @failures)
  (println "ALL TESTS PASSED")
  (do (println (str @failures " FAILURES"))
      (System/exit 1)))
