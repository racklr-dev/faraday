;; faraday.harness.llm test: token-usage plumbing for /cost.
;; response-usage reads the usage map from a parsed response; add-usage
;; accumulates token totals and counts requests; completion->result splits the
;; action and usage out of one HTTP call so a cost-tracking caller does not
;; re-call the model. Hermetic: with-redefs stubs curl-http, so no network.
;; Run: cd harness && jolt run test/faraday/llm_test.clj
(ns jolt.test.llm
  (:require [clojure.data.json :as json]
            [faraday.harness.llm :as llm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- response-usage (pure) ----------------------------------------------------
(check "response-usage reads token fields"
       {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}
       (llm/response-usage {:usage {:prompt_tokens 10
                                    :completion_tokens 5
                                    :total_tokens 15
                                    :completion_tokens_details {:reasoning 0}}}))

(check "response-usage is nil when the provider omitted usage"
       nil
       (llm/response-usage {:choices []}))

(check "response-usage ignores non-token usage keys"
       {:prompt_tokens 1 :completion_tokens 2 :total_tokens 3}
       (llm/response-usage {:usage {:prompt_tokens 1 :completion_tokens 2 :total_tokens 3 :extra 9}}))

;; -- add-usage (pure) ---------------------------------------------------------
(check "add-usage sums tokens and counts each request"
       {:prompt_tokens 30 :completion_tokens 15 :total_tokens 45 :requests 2}
       (-> {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0 :requests 0}
           (llm/add-usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15})
           (llm/add-usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30})))

(check "add-usage counts a nil-usage call as a request"
       {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15 :requests 2}
       (-> {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0 :requests 0}
           (llm/add-usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15})
           (llm/add-usage nil)))

;; -- completion->result splits action + usage from one response ---------------
(let [content (pr-str {:done "ok"})
      body (json/write-str {:choices [{:message {:content content}}]
                            :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}})]
  (with-redefs [llm/curl-http (fn [_ _] body)]
    (check "completion->result returns the action and usage together"
           {:action {:done "ok"} :usage {:prompt_tokens 7 :completion_tokens 3 :total_tokens 10}}
           (llm/completion->result {} []))
    (check "completion->action keeps the historical action-only contract"
           {:done "ok"}
           (llm/completion->action {} []))))

(let [body (json/write-str {:choices [{:message {:tool_calls [{:function {:name "board" :arguments "{}"}}]}}]
                            :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}})]
  (with-redefs [llm/curl-http (fn [_ _] body)]
    (check "completion->result reads native tool_calls and usage"
           {:action {:tool-call {:name "board" :args {}}}
            :usage {:prompt_tokens 2 :completion_tokens 1 :total_tokens 3}}
           (llm/completion->result {} []))))

(println "ALL LLM TESTS PASSED")
