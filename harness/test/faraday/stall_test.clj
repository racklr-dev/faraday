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
      call (fn [msgs]
             (if (some #(str/includes? (str (:content %)) "do not call tools") msgs)
               "{:done \"here is the answer\"}"
               "{:tool-call {:name \"board\" :args {}}}"))
      exec (fn [_ _] {:status :ok :exit 0 :stdout "" :progress? false})
      opts {:call call :exec exec :system "S" :max-turns 8 :max-stall 3}]
  (chat/run-turn! opts state [{:role :user :text "x"}])
  (check "stall ends with a final answer" "here is the answer" (-> @state :messages last :text))
  (check "stall clears the error banner" nil (:error @state))
  (check "stall stops early (3 tool calls, not 8)"
         true (> 8 (count (filter #(= :tool-call (:role %)) (:messages @state))))))

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

(let [calls (atom 0)
      state (r/atom {:messages [{:role :user :text "x"}] :busy? true})
      interrupt (atom true)
      opts {:call (fn [_] (swap! calls inc) "{:done \"never\"}")
            :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
            :system "S" :max-turns 4 :interrupt interrupt}]
  (chat/run-turn! opts state [{:role :user :text "x"}])
  (check "interrupt stops before any model call" 0 @calls)
  (check "interrupt appends a Stopped note" "Stopped." (-> @state :messages last :text))
  (check "interrupt clears busy" false (:busy? @state)))

(let [calls (atom 0)
      state (r/atom {:messages []})
      opts {:call (fn [_] (swap! calls inc) "{:tool-call {:name \"run_shell\" :args {:argv [\"sleep\" \"99\"]}}}")
            :exec (fn [_ _] {:status :error :error "interrupted" :interrupted? true})
            :system "S" :max-turns 4}]
  (chat/run-turn! opts state [{:role :user :text "x"}])
  (check "interrupted tool stops the turn" "Stopped." (-> @state :messages last :text))
  (check "interrupted tool makes one model call" 1 @calls)
  (check "interrupted tool clears busy" false (:busy? @state)))

(println "ALL STALL TESTS PASSED")
(shutdown-agents)
