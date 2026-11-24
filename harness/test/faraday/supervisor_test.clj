;; faraday-harness supervisor test: the self-improvement gate.
;; observe -> propose -> validate+soak+rollback -> persist -> record.
;; The budget-relaxer improves a manifest that exhausts :max-turns; an
;; already-green loop reports :no-improvement; a loop that fails for a reason
;; the proposer cannot fix reports :no-proposal (never a guess).
;; Run: cd harness && jolt run test/faraday/supervisor_test.clj
(ns jolt.test.supervisor
  (:require [faraday.harness :as h]
            [faraday.harness.supervisor :as sup]
            [faraday.harness.main :as main]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-root []
  (str (System/getProperty "java.io.tmpdir") "/faraday-supervisor-" (System/nanoTime)))

(defn setup! []
  (let [root (tmp-root)
        _ (.mkdirs (java.io.File. (str root "/.faraday")))
        hd (h/new-harness {:path (str root "/.faraday/harness.db")})
        p (h/project-add! hd {:root-path root :name "t" :default-manifest "default"})
        rs (h/scope-add! hd {:project-id (:id p) :rel-path "" :is-root true})]
    {:root root :hd hd :project p :root-scope rs}))

(defn budget-manifest [max-turns]
  {:v 1
   :loop-id "budget"
   :name "budget loop"
   :agent {:kind "script"
           :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "one"]} :display-as "e1"}}
                    {:tool-call {:name "run_shell" :args {:argv ["echo" "two"]} :display-as "e2"}}
                    {:done {:result :ok}}]}
   :max-turns max-turns})

;; -- the budget-relaxer improves a manifest that exhausts its budget ---------
(let [{:keys [root hd project]} (setup!)
      task (h/task-add! hd {:project-id (:id project)
                            :title "budget" :manifest (budget-manifest 1)})
      before (main/run-task! root (:id task))
      out (sup/improve! {:root root :task-id (:id task) :n 3})]
  (check "honest run honors stored manifest (exhausts budget)" :max-turns (:agent-status before))
  (check "improve! status" :improved (:status out))
  (check "proposal raises max-turns 1->5" {:max-turns 5 :from 1} (:proposal out))
  (check "gate promoted" :promoted (get-in out [:gate :status]))
  (check "task manifest persisted" 5 (:max-turns (:manifest (h/task hd (:id task)))))
  (let [runs (h/supervisor-runs-for-task hd (:id task))]
    (check "one supervisor run recorded" 1 (count runs))
    (check "recorded verdict improved" :improved (:verdict (first runs)))
    (check "recorded baseline not passed" false (:baseline_passed (first runs))))
  ;; the improved manifest now runs to done through the honest path
  (let [run (main/run-task! root (:id task))]
    (check "improved manifest runs done" :done (:agent-status run))))

;; -- an already-green loop has nothing to improve -----------------------------
(let [{:keys [root hd project]} (setup!)
      task (h/task-add! hd {:project-id (:id project) :title "already good"})
      out (sup/improve! {:root root :task-id (:id task) :n 3})]
  (check "green baseline reports no-improvement" :no-improvement (:status out))
  (check "green baseline reason" :baseline-green-no-proposal (:reason out))
  (check "green task manifest unchanged" nil (:manifest (h/task hd (:id task)))))

;; -- a failure the proposer cannot fix reports no-proposal, never a guess ----
(let [{:keys [root hd project]} (setup!)
      task (h/task-add! hd {:project-id (:id project)
                            :title "bogus"
                            :manifest {:v 1 :loop-id "bogus" :name "bogus"
                                       :agent {:kind "script" :script [{:bogus 1}]}
                                       :max-turns 8}})
      out (sup/improve! {:root root :task-id (:id task) :n 3})]
  (check "unfixable failure reports no-proposal" :no-proposal (:status out))
  (check "unfixable reason" :no-mechanical-rewrite (:reason out))
  (check "unfixable task manifest unchanged" {:bogus 1} (get-in (h/task hd (:id task)) [:manifest :agent :script 0])))

(println "ALL SUPERVISOR TESTS PASSED")
