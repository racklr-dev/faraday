;; jolt-rap stub test: pins the public API surface.
;; Run: cd jolt-rap && jolt run test/jolt/rap_test.clj
(ns jolt.test.rap
  (:require [jolt.rap :as rap]))

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

(def sample-call
  {:v 1 :call-id "call_1" :thread-id "thr_1" :name "run_shell" :args {:argv ["ls"]}})

(check "new-server defined" true (fn? rap/new-server))
(check "new-server stub" true (stub? (stub-err #(rap/new-server {:vm-pool nil :fanout nil}))))
(check "invoke stub" true (stub? (stub-err #(rap/invoke nil sample-call))))
(check "cancel stub" true (stub? (stub-err #(rap/cancel nil "call_1"))))
(check "close stub" true (stub? (stub-err #(rap/close nil "call_1"))))
(check "subscribe stub" true (stub? (stub-err #(rap/subscribe nil "call_1" identity))))
(check "on-result stub" true (stub? (stub-err #(rap/on-result nil "call_1" identity))))
(check "close! stub" true (stub? (stub-err #(rap/close! nil))))
