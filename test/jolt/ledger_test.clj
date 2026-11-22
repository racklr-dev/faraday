;; jolt-ledger stub test: pins the public API surface.
;; Run: cd jolt-ledger && jolt run test/jolt/ledger_test.clj
(ns jolt.test.ledger
  (:require [jolt.ledger :as ledger]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn stub-err [thunk]
  (try (thunk) ::no-throw
       (catch Exception e (ex-message e))))

(defn stub? [msg]
  (boolean (re-find #"^not implemented" (or msg ""))))

(check "new-ledger defined" true (fn? ledger/new-ledger))
(check "new-ledger stub" true (stub? (stub-err #(ledger/new-ledger {:path "/tmp/x"}))))
(check "append! stub" true (stub? (stub-err #(ledger/append! nil {}))))
(check "history stub" true (stub? (stub-err #(ledger/history nil "t1"))))
(check "processed? stub" true (stub? (stub-err #(ledger/processed? nil "m1"))))
(check "last-seq stub" true (stub? (stub-err #(ledger/last-seq nil "t1"))))
(check "close! stub" true (stub? (stub-err #(ledger/close! nil))))
