;; faraday-harness stall guard: a model that keeps making valid-but-useless
;; (read-only) tool calls must stop early with a clear error, not grind to
;; :max-turns. Hermetic (fake call/exec seams).
(ns jolt.test.stall
  (:require [faraday.harness.chat :as chat]
            [glimmer.ratom :as r]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(let [state (r/atom {:messages []})
      call (fn [_] "{:tool-call {:name \"board\" :args {}}}")
      exec (fn [_ _] {:status :ok :exit 0 :stdout "" :progress? false})
      opts {:call call :exec exec :system "S" :max-turns 8 :max-stall 3}]
  (chat/run-turn! opts state [{:role :user :text "x"}])
  (check "stall stops with a clear error" "no progress — stopping" (:error @state))
  (check "stall ran fewer than max turns" true (> 8 (count (:messages @state)))))

(let [b (atom {:failures 0 :open? false})
      f (#'chat/with-circuit-breaker b (fn [_] ""))]
  (f nil) (f nil)
  (let [r (f nil)]
    (check "circuit opens after 3 give-ups" true (str/includes? (str (:done r)) "circuit open")))
  (let [r (f nil)]
    (check "open circuit short-circuits" true (str/includes? (str (:done r)) "circuit open"))))

(let [b (atom {:failures 0 :open? false})
      f (#'chat/with-circuit-breaker b (fn [x] (if (= x :good) "ok" "")))]
  (f nil) (f nil) (f :good)
  (check "a good answer resets the breaker" false (:open? @b)))

(println "ALL STALL TESTS PASSED")
(shutdown-agents)
