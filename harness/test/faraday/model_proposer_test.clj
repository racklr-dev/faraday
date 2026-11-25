;; faraday-harness model-proposer test: the model-driven self-improvement seam.
;; observe -> propose -> gate. The :http transport is stubbed (a fake returns a
;; canned chat-completion JSON body), so the suite needs no live API key. It
;; covers config resolution, EDN parsing, fence stripping, :no-proposal -> nil,
;; honest throws on empty/non-manifest content and a missing key, and one
;; end-to-end improve! run whose :proposer is the model proposer.
;; Run: cd harness && jolt run test/faraday/model_proposer_test.clj
(ns jolt.test.model-proposer
  (:require [faraday.harness :as h]
            [faraday.harness.supervisor :as sup]
            [faraday.harness.model-proposer :as mp]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-root []
  (str (System/getProperty "java.io.tmpdir") "/faraday-mp-" (System/nanoTime)))

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

(defn fake-http [content]
  (fn [cfg messages]
    (json/write-str {:choices [{:message {:content content}}]})))

(defn no-key-env? []
  (every? nil? [(System/getenv "FARADAY_LLM_API_KEY")
                (System/getenv "FARADAY_LLM_KEY")
                (System/getenv "DEEPSEEK_API_KEY")]))

;; -- config resolution --------------------------------------------------------
(let [nf "/nonexistent-faraday-settings"
      cfg (mp/resolve-config {:settings-file nf :auth-file nf})]
  (check "default base-url" "https://api.deepseek.com/v1" (:base-url cfg))
  (check "default model" "deepseek-flash" (:model cfg))
  (when (no-key-env?)
    (check "no key by default" nil (:api-key cfg))))

;; -- the model proposer returns a candidate manifest --------------------------
(let [candidate (budget-manifest 7)
      prop (mp/model-proposer {:api-key "test-key" :http (fake-http (pr-str candidate))})
      got (prop (budget-manifest 1) {:status :max-turns})]
  (check "model proposer returns candidate" candidate got))

;; -- :no-proposal -> nil ------------------------------------------------------
(let [prop (mp/model-proposer {:api-key "test-key" :http (fake-http ":no-proposal")})
      got (prop (budget-manifest 1) {:status :max-turns})]
  (check "model proposer returns nil on :no-proposal" nil got))

;; -- markdown fences are stripped ---------------------------------------------
(let [prop (mp/model-proposer {:api-key "test-key"
                               :http (fake-http "```clojure\n{:v 1 :max-turns 7}\n```")})
      got (prop (budget-manifest 1) {:status :max-turns})]
  (check "fences stripped" 7 (:max-turns got)))

;; -- honest throws on bad model output ----------------------------------------
(let [prop (mp/model-proposer {:api-key "test-key" :http (fake-http "42")})
      threw? (try (prop (budget-manifest 1) {:status :max-turns}) false
                  (catch Exception e true))]
  (check "non-manifest EDN throws" true threw?))

(let [prop (mp/model-proposer {:api-key "test-key" :http (fake-http "   ")})
      threw? (try (prop (budget-manifest 1) {:status :max-turns}) false
                  (catch Exception e true))]
  (check "empty content throws" true threw?))

(when (no-key-env?)
  (let [threw? (try (mp/model-proposer {:settings-file "/nonexistent-faraday-settings"
                                         :auth-file "/nonexistent-faraday-auth"})
                    false (catch Exception e true))]
    (check "missing key throws" true threw?)))

;; -- end-to-end: improve! with the model proposer as the :proposer seam -------
(let [{:keys [root hd project]} (setup!)
      task (h/task-add! hd {:project-id (:id project)
                            :title "model" :manifest (budget-manifest 1)})
      prop (mp/model-proposer {:api-key "test-key"
                               :http (fake-http (pr-str (budget-manifest 7)))})
      out (sup/improve! {:root root :task-id (:id task) :n 3 :proposer prop})]
  (check "model improve! status" :improved (:status out))
  (check "model proposal 1->7" {:max-turns 7 :from 1} (:proposal out))
  (check "model task manifest persisted" 7 (:max-turns (:manifest (h/task hd (:id task))))))

(println "ALL MODEL-PROPOSER TESTS PASSED")

(shutdown-agents)
