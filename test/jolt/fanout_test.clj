;; jolt-fanout stub test: pins the public API surface.
;; Run: cd jolt-fanout && jolt run test/jolt/fanout_test.clj
(ns jolt.test.fanout
  (:require [jolt.fanout :as fanout]))

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

(check "new-runtime defined" true (fn? fanout/new-runtime))
(check "new-runtime stub" true (stub? (stub-err #(fanout/new-runtime {:ledger nil}))))
(check "spawn stub" true (stub? (stub-err #(fanout/spawn nil nil {:argv ["x"]} nil))))
(check "inherit stub" true (stub? (stub-err #(fanout/inherit nil "t1" "snap"))))
(check "report stub" true (stub? (stub-err #(fanout/report nil "t1" "ok" nil nil))))
(check "yield stub" true (stub? (stub-err #(fanout/yield nil "t1" "h" "snap"))))
(check "emit stub" true (stub? (stub-err #(fanout/emit nil {}))))
(check "close! stub" true (stub? (stub-err #(fanout/close! nil))))
