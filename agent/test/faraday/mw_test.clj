;; faraday-agent middleware spine test: the ROADMAP wrap-* chain compiled from
;; a manifest + deps. Hermetic: no rap/scheduler/KVM — each wrapper is called
;; directly, and compile-step-fn runs against a fake hook runner + recorder.
;; Proves: history windowing, repair honesty, permission denial, the goal judge,
;; per-cell guardrail firing, and a blocking pre-hook veto.
;; Run: cd faraday/agent && jolt run test/faraday/mw_test.clj
(ns jolt.test.mw
  (:require [faraday.agent.mw :as mw]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn script-manifest []
  {:v 1 :loop-id "mw" :name "mw spine"
   :agent {:kind "script" :script [{:done {:result :ok}}]}})

;; -- wrap-history ------------------------------------------------------------
(let [f ((mw/wrap-history {:window 4}) identity)
      ctx {:transcript (vec (range 10))}
      out (:transcript (f ctx))]
  (check "history windows to the budget" 4 (count out))
  (check "window keeps the task (first entry)" 0 (first out))
  (check "window keeps the tail" 9 (peek out)))

;; -- wrap-compress -----------------------------------------------------------
(let [f ((mw/wrap-compress {:compress-threshold 4}) identity)
      ctx {:transcript (vec (range 8))}
      out (:transcript (f ctx))]
  (check "compress folds the middle to 3 entries" 3 (count out))
  (check "compressed entry carries the fold" :compressed (:role (second out)))
  (check "fold counts the middle" 6 (get-in (second out) [:content :count])))

;; -- wrap-repair -------------------------------------------------------------
(let [f ((mw/wrap-repair {}) (fn [_] {:bogus true}))
      a (f {})]
  (check "repair turns a malformed action into an honest error"
         :step-invalid (get-in a [:done :error])))

(let [f ((mw/wrap-repair {}) (fn [_] {:tool-call {:name "run" :args {:argv ["echo" "hi"]}}}))
      a (f {})]
  (check "repair passes a valid tool-call through" "run" (get-in a [:tool-call :name])))

;; -- wrap-permission ---------------------------------------------------------
(let [f ((mw/wrap-permission {:decide (fn [_ _] {:allow? false :why "nope"})})
         (fn [_] {:tool-call {:name "run" :args {:argv ["x"]}}}))
      a (f {})]
  (check "permission denial stops the step" :denied (get-in a [:done :error]))
  (check "denial carries the why" "nope" (get-in a [:done :why])))

(let [d (mw/rule-decide [{:op :tool-call :name #"^rm" :allow? false :why "no rm"}])]
  (check "op rule denies a matching tool" false
         (:allow? (d {} {:tool-call {:name "rm" :args {:argv ["-rf" "/"]}}})))
  (check "op rule allows a non-matching tool" true
         (:allow? (d {} {:tool-call {:name "ls" :args {:argv ["/tmp"]}}}))))

;; -- wrap-goal (the independent judge at finalization) ------------------------
(let [f ((mw/wrap-goal {:goal-fn (constantly true)}) (fn [_] {:done {:result :ok}}))
      a (f {})]
  (check "goal judge marks the done action met" true (get-in a [:done :goal-met]))
  (check "goal judge says it judged" true (get-in a [:done :goal-judged])))

;; -- wrap-guardrails: per-cell firing + recording -----------------------------
(let [runs (atom [])
      hooks [{:id "h-orient" :cell "orient" :position "pre" :kind "check"
              :gate "blocking" :how {:command ["true"]} :why "orient check"}
             {:id "h-fin" :cell "finalize" :position "post" :kind "gate"
              :gate "blocking" :how {:command ["true"]} :why "finalize gate"}]
      step (mw/compile-step-fn
            (script-manifest)
            {:hooks hooks
             :record! (fn [r] (swap! runs conj r))
             :run-command (fn [_ _] {:exit 0 :out "" :timeout? false})})
      a (step {:thread-id "thr1" :turns 0
               :transcript [{:role :user :content "task"}]})]
  (check "script step returns done through the spine" :ok (get-in a [:done :result]))
  (check "orient pre and finalize post both ran" 2 (count @runs))
  (check "the right cells fired" #{"orient" "finalize"} (set (map :cell @runs)))
  (check "positions recorded" #{"pre" "post"} (set (map :position @runs)))
  (check-true "every verdict passed" (every? #(= :pass (:verdict %)) @runs)))

;; -- wrap-guardrails: a blocking pre hook vetoes the step ---------------------
(let [runs (atom [])
      hooks [{:id "h-step" :cell "step" :position "pre" :kind "gate"
              :gate "blocking" :how {:command ["false"]} :why "block the step"}]
      step (mw/compile-step-fn
            (script-manifest)
            {:hooks hooks
             :record! (fn [r] (swap! runs conj r))
             :run-command (fn [_ _] {:exit 1 :out "" :timeout? false})})
      a (step {:thread-id "thr1" :turns 1
               :transcript [{:role :user :content "task"}
                            {:role :tool-result :status "ok" :segments []}]})]
  (check "blocking pre hook vetoes" :guardrail-blocked (get-in a [:done :error]))
  (check "veto names the offending hook" "h-step" (get-in a [:done :vetoes 0 :id]))
  (check "the failed hook still recorded" 1 (count @runs))
  (check "the failed verdict is fail" :fail (:verdict (first @runs))))

;; -- a goal-met stop still finalizes (finalize hooks fire) --------------------
(let [runs (atom [])
      hooks [{:id "h-fin" :cell "finalize" :position "post" :kind "gate"
              :gate "blocking" :how {:command ["true"]} :why "finalize gate"}]
      step (mw/compile-step-fn
            (script-manifest)
            {:goal-fn (constantly true)
             :hooks hooks
             :record! (fn [r] (swap! runs conj r))
             :run-command (fn [_ _] {:exit 0 :out "" :timeout? false})})
      a (step {:thread-id "thr1" :turns 0
               :transcript [{:role :user :content "task"}]})]
  (check "goal judge stops the loop" :goal-met (get-in a [:done :result]))
  (check "finalize hook fires on a goal-met stop" 1 (count @runs))
  (check "finalize cell recorded" "finalize" (:cell (first @runs))))

(println "ALL MW TESTS PASSED")
