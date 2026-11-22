;; jolt-ledger smoke: minimal critical-path gate for CI.
;; Topic (board nc-71FC): idempotent redelivery survives the durable boundary.
;; Fail-fast: the first mismatch prints and exits 1.
;; Run: cd jolt-ledger && jolt run test/jolt/ledger_smoke.clj
(ns jolt.smoke.ledger
  (:require [jolt.ledger :as ledger]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn tmp-path []
  (str (System/getProperty "java.io.tmpdir")
       "/jolt-ledger-smoke-" (System/nanoTime) "/ledger.db"))

(defn msg [thread-id msg-id & {:as overrides}]
  (merge {:v 1
          :thread-id thread-id
          :parent-id nil
          :msg-id msg-id
          :kind "spawn"
          :ts "2026-11-22T07:24:19.907Z"
          :payload {}}
         overrides))

(let [path (tmp-path)]
  ;; -- phase 1: fresh ledger, append + redeliver --------------------------
  (let [db (ledger/new-ledger {:path path})]
    (try
      (check "first append assigns seq 0"
             {:seq 0 :deduped? false}
             (ledger/append! db (msg "t1" "smoke-1")))
      ;; the same msg-id with a different payload/ts is still the same message
      (check "redelivery is an idempotent no-op"
             {:seq 0 :deduped? true}
             (ledger/append! db (msg "t1" "smoke-1" :payload {:second true})))
      (check "history holds one row, not two"
             1 (count (ledger/history db "t1")))
      (check "last-seq did not advance on redelivery"
             0 (ledger/last-seq db "t1"))
      (check "processed? sees the msg-id"
             true (ledger/processed? db "smoke-1"))
      (check "the stored row keeps the first payload"
             {} (:payload (first (ledger/history db "t1"))))
      (finally (ledger/close! db))))

  ;; -- phase 2: reopen, redeliver against persisted state ------------------
  (let [db (ledger/new-ledger {:path path})]
    (try
      (check "reopen: redelivery dedups against persisted state"
             {:seq 0 :deduped? true}
             (ledger/append! db (msg "t1" "smoke-1")))
      (check "reopen: history still one row"
             1 (count (ledger/history db "t1")))
      (check "reopen: next append continues at seq 1"
             {:seq 1 :deduped? false}
             (ledger/append! db (msg "t1" "smoke-2")))
      (check "reopen: last-seq is now 1"
             1 (ledger/last-seq db "t1"))
      (finally (ledger/close! db)))))

(println "LEDGER SMOKE PASSED")
