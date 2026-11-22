;; jolt-memo stub test: pins the public API surface.
;; Run: cd jolt-memo && jolt run test/jolt/memo_test.clj
(ns jolt.test.memo
  (:require [jolt.memo :as memo]))

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

(check "new-memo defined" true (fn? memo/new-memo))
(check "new-memo stub" true (stub? (stub-err #(memo/new-memo {:path "/tmp/x"}))))
(check "key-of stub" true (stub? (stub-err #(memo/key-of nil {:a 1}))))
(check "put! stub" true (stub? (stub-err #(memo/put! nil {:a 1}))))
(check "get stub" true (stub? (stub-err #(memo/get nil "k"))))
(check "contains? stub" true (stub? (stub-err #(memo/contains? nil "k"))))
(check "delete! stub" true (stub? (stub-err #(memo/delete! nil "k"))))
(check "stats stub" true (stub? (stub-err #(memo/stats nil))))
(check "close! stub" true (stub? (stub-err #(memo/close! nil))))
