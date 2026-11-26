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

;; -- cache usage (dialect-aware) ---------------------------------------------
(check "cache-usage reads the OpenAI chat cache fields"
       {:cached-input-tokens 20 :cache-write-tokens 100 :cache-write-5m-tokens 60
        :cache-write-1h-tokens 40 :reasoning-tokens 7}
       (llm/cache-usage {:prompt_tokens 120 :completion_tokens 30 :total_tokens 150
                         :prompt_tokens_details {:cached_tokens 20}
                         :cache_creation_input_tokens 100
                         :cache_write_5m_tokens 60
                         :cache_write_1h_tokens 40
                         :completion_tokens_details {:reasoning_tokens 7}}))

(check "cache-usage reads the Anthropic/relay spellings"
       {:cached-input-tokens 20 :cache-write-tokens 100}
       (llm/cache-usage {:cache_read_tokens 20 :cache_write_tokens 100}))

(check "cache-usage is empty when the provider reports no cache"
       {}
       (llm/cache-usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15}))

(check "response-usage adds the cache split when reported"
       {:prompt_tokens 120 :completion_tokens 30 :total_tokens 150
        :cached-input-tokens 20 :cache-write-tokens 100}
       (llm/response-usage {:usage {:prompt_tokens 120 :completion_tokens 30 :total_tokens 150
                                    :prompt_tokens_details {:cached_tokens 20}
                                    :cache_creation_input_tokens 100}}))

(check "uncached-prompt-tokens subtracts the cache read"
       100
       (llm/uncached-prompt-tokens {:prompt_tokens 120 :cached-input-tokens 20}))

(check "add-usage accumulates cache fields too"
       {:prompt_tokens 30 :completion_tokens 15 :total_tokens 45 :requests 2
        :cached-input-tokens 40 :cache-write-tokens 20}
       (-> {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0 :requests 0}
           (llm/add-usage {:prompt_tokens 10 :completion_tokens 5 :total_tokens 15
                           :cached-input-tokens 15 :cache-write-tokens 20})
           (llm/add-usage {:prompt_tokens 20 :completion_tokens 10 :total_tokens 30
                           :cached-input-tokens 25})))

(check "cache-usage reads DeepSeek's prompt_cache_hit_tokens"
       {:cached-input-tokens 40}
       (llm/cache-usage {:prompt_cache_hit_tokens 40 :prompt_cache_miss_tokens 80}))

;; -- streaming: deltas reassemble to the same result as the plain body --------
(let [content (pr-str {:done "ok"})
      body (json/write-str
            {:choices [{:index 0 :message {:role "assistant" :content content}}]
             :usage {:prompt_tokens 3 :completion_tokens 2 :total_tokens 5}})
      deltas (atom [])]
  (with-redefs [llm/curl-http (fn [_ _] body)
                llm/stream-http (fn [_ _ on-delta]
                                  (on-delta "think")
                                  (json/read-str body :key-fn keyword))]
    (check "plain completion parses the action"
           {:done "ok"}
           (:action (llm/completion->result {} [])))
    (check "streamed completion reassembles to the same action"
           {:done "ok"}
           (:action (llm/completion->result {:stream true} []
                                            (fn [t] (swap! deltas conj t)))))
    (check "on-delta saw the running thinking text"
           ["think"] @deltas)))

;; -- content->action: non-canonical tool-call formats models actually emit ----
(check "content->action reads DSML with a string attr on parameter"
       {:tool-call {:name "run_shell" :args {:argv ["ls" "-la"]}}}
       (llm/content->action
        "<｜DSML｜ calls>\n<｜DSML｜ invoke name=\"run_shell\">\n<｜DSML｜ parameter name=\"argv\" string=\"true\">[\"ls\", \"-la\"]</｜DSML｜ parameter>\n</｜DSML｜ invoke>\n</｜DSML｜ calls>"))

(check "content->action reads OpenAI-style XML tool_calls"
       {:tool-call {:name "run_shell" :args {:argv ["head" "-n" "5" "README.md"]}}}
       (llm/content->action
        "<tool_calls>\n<invoke name=\"run_shell\">\n<parameter name=\"argv\">[\"head\", \"-n\", \"5\", \"README.md\"]</parameter>\n</invoke>\n</tool_calls>"))

(check "content->action reads a bare tool-name key as args"
       {:tool-call {:name "run_shell" :args {:argv ["ls" "-la"]}}}
       (llm/content->action "{:run_shell {:argv [\"ls\" \"-la\"]}}"))

(check "content->action still reads the canonical tool-call EDN"
       {:tool-call {:name "run_shell" :args {:argv ["ls"]}}}
       (llm/content->action (pr-str {:tool-call {:name "run_shell" :args {:argv ["ls"]}}})))

(println "ALL LLM TESTS PASSED")
