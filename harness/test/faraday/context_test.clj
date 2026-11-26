;; faraday.harness.context test: context-window measurement, ported from
;; nodecode's context engine. Estimate = chars/4 of JSON, calibrated per
;; (provider, model) by each round's measured prompt over its estimate; the
;; window is a learned belief (floor from processed prompts, ceiling from
;; refusals); pressure is :unknown/:ok/:pressure at the 0.85 trigger.
;; Hermetic: every stateful fn takes an explicit atom, so no globals are read.
;; Run: cd harness && jolt run test/faraday/context_test.clj
(ns jolt.test.context
  (:require [faraday.harness.context :as ctx]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- estimation: chars/4 -----------------------------------------------------
(check "estimate-json-tokens is chars/4, rounded up"
       2
       (ctx/estimate-json-tokens 12345))

(check "estimate-json-tokens rounds a 4-char value to 1"
       1
       (ctx/estimate-json-tokens 1234))

(let [msgs [{:role "system" :content "s"} {:role "user" :content "hi"}]]
  (check "estimate-messages-tokens is the whole array's chars/4"
         (ctx/estimate-json-tokens msgs)
         (ctx/estimate-messages-tokens msgs)))

;; -- calibration -------------------------------------------------------------
(let [st (atom {})]
  (ctx/observe-context-estimate! st "deepseek" "deepseek-flash" 1000 1300)
  (check "calibration ratio is measured/estimated"
         1.3
         (ctx/context-estimate-ratio st "deepseek" "deepseek-flash"))
  (check "an unobserved model defaults to ratio 1.0"
         1.0
         (ctx/context-estimate-ratio st "deepseek" "other"))
  (ctx/observe-context-estimate! st "deepseek" "zero" 0 5)
  (check "a non-positive estimate is not observed"
         1.0
         (ctx/context-estimate-ratio st "deepseek" "zero")))

;; -- pressure ----------------------------------------------------------------
(check "pressure is :unknown without a budget"
       {:status :unknown :estimated 100 :budget nil :trigger nil}
       (ctx/context-pressure-decision 100 nil))

(check "pressure is :ok under the 0.85 trigger"
       {:status :ok :estimated 80 :budget 100 :trigger 85.0}
       (ctx/context-pressure-decision 80 100))

(check "pressure trips at 85% of the window"
       {:status :pressure :estimated 86 :budget 100 :trigger 85.0}
       (ctx/context-pressure-decision 86 100))

;; -- learned budget ----------------------------------------------------------
(let [st (atom {})]
  (ctx/observe-context-floor! st "deepseek" "flash" 50000)
  (check "a proven floor raises the prior"
         60000
         (ctx/context-window-belief st "deepseek" "flash" 60000 false))
  (check "a floor never lowers the prior"
         50000
         (ctx/context-window-belief st "deepseek" "flash" 40000 false))
  (ctx/observe-context-ceiling! st "deepseek" "flash" 70000)
  (check "a ceiling caps a larger prior"
         70000
         (ctx/context-window-belief st "deepseek" "flash" 100000 false))
  (check "an explicit operator prior is verbatim"
         200000
         (ctx/context-window-belief st "deepseek" "flash" 200000 true))
  (check "a nil prior stays nil until a ceiling; then the ceiling"
         70000
         (ctx/context-window-belief st "deepseek" "flash" nil false)))

;; -- readout -----------------------------------------------------------------
(let [cal (atom {}) win (atom {})]
  (ctx/observe-context-estimate! cal "deepseek" "flash" 100 130)
  (ctx/observe-context-floor! win "deepseek" "flash" 1000)
  (let [msgs [{:role "system" :content "s"} {:role "user" :content "hi"}]
        r (ctx/context-estimate cal win
                                {:provider "deepseek" :model "flash" :context-tokens 2000}
                                msgs)]
    (check "context-estimate scales by the calibration ratio"
           (long (Math/round (* 1.3 (ctx/estimate-messages-tokens msgs))))
           (:estimated r))
    (check "context-estimate reads the learned budget"
           2000
           (:budget r))
    (check "context-estimate is :ok under the trigger"
           :ok
           (:status r))
    (check "context-estimate splits standing vs history"
           {:messages (ctx/estimate-messages-tokens msgs)
            :standing (long (Math/round (* 1.3 (ctx/estimate-json-tokens [{:role "system" :content "s"}]))))
            :history (long (Math/round (* 1.3 (ctx/estimate-json-tokens [{:role "user" :content "hi"}]))))}
           (:parts r))))

;; -- keyword providers normalize to the same key as strings -------------------
(let [cal (atom {}) win (atom {})]
  (ctx/observe-context-estimate! cal :deepseek "m" 1000 1300)
  (check "a keyword provider keys like its string"
         1.3
         (ctx/context-estimate-ratio cal "deepseek" "m"))
  (ctx/observe-context-floor! win "deepseek" :m 5000)
  (check "floor is read across the same key"
         5000
         (ctx/context-window-floor win :deepseek "m")))

(check "context-window-floor is nil until observed"
       nil
       (ctx/context-window-floor (atom {}) "deepseek" "m"))

(let [cal (atom {}) win (atom {})]
  (ctx/observe-context-floor! win :deepseek :m 9000)
  (let [r (ctx/context-estimate cal win
                                {:provider :deepseek :model "m"}
                                [{:role "system" :content "s"}])]
    (check "context-estimate carries the proven floor"
           9000
           (:floor r))))

(println "ALL CONTEXT TESTS PASSED")