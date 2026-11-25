;; faraday-harness functional test: REAL calls against DeepSeek flash.
;; Not hermetic: needs a resolvable DeepSeek key (env or ~/.nodecode/auth.json);
;; skips (exit 0) when none is resolvable so CI stays green. Two real calls:
;;   1. default provider (key from auth) -> chat/completions -> EDN action
;;   2. token-helper provider ("!python3 -c '...'") -> same, proving the "!"
;;      command path yields a working key end-to-end.
;; Run: cd harness && jolt run test/faraday/functional_test.clj
(ns jolt.test.functional
  (:require [faraday.harness.config :as c]
            [faraday.harness.llm :as llm]
            [faraday.harness.tools :as tools]
            [clojure.data.json :as json]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(def nf "/nonexistent-faraday-config")

(defn tmpfile [m]
  (let [f (str (System/getProperty "java.io.tmpdir") "/faraday-func-" (System/nanoTime) ".json")]
    (spit f (json/write-str m))
    f))

(defn real-key-present? []
  (boolean (:api-key (c/resolve-provider (c/load-config {}) :deepseek {}))))

(def prompt {:system "You are faraday's functional test. Obey the guidance exactly."
             :context {:task "functional test"}
             :guidance "Respond with exactly one EDN map: {:done :ok} and nothing else."})

(defn call! [provider] (provider prompt))

;; hermetic: faraday :reasoning -> DeepSeek :effort mapping in the request body
(check "request-body: reasoning max -> effort max" "max"
       (:effort (llm/request-body {:model "m" :reasoning "max"} [])))
(check "request-body: reasoning high -> effort high" "high"
       (:effort (llm/request-body {:model "m" :reasoning "high"} [])))
(check "request-body: no reasoning -> no effort" nil
       (:effort (llm/request-body {:model "m"} [])))
(check "request-body: unsupported reasoning dropped" nil
       (:effort (llm/request-body {:model "m" :reasoning "extreme"} [])))
(check "request-body: native tools + tool-choice"
       [{:type "function"}]
       (:tools (llm/request-body {:model "m" :tools [{:type "function"}]} [])))
(check "request-body: tool-choice defaults to auto"
       "auto"
       (:tool-choice (llm/request-body {:model "m" :tools []} [])))
(check "openai-tools: board schema present"
       true
       (some #(= "board" (get-in % [:function :name])) (tools/openai-tools)))

;; hermetic: DeepSeek DSML tool calls parse into the action contract
(check "dsml: single self-closing invoke"
       {:tool-call {:name "board" :args {}}}
       (llm/content->action "<｜DSML｜ invoke name=\"board\" />"))
(check "dsml: multiple invokes"
       {:tool-calls [{:name "board" :args {}} {:name "task_list" :args {}}]}
       (llm/content->action "<｜DSML｜ invoke name=\"board\" />\n<｜DSML｜ invoke name=\"task_list\" />"))
(check "dsml: open invoke with EDN parameter"
       {:tool-call {:name "run_shell" :args {:argv ["ls"]}}}
       (llm/content->action
        "<｜DSML｜ invoke name=\"run_shell\"><｜DSML｜ parameter name=\"argv\">[\"ls\"]</｜DSML｜ parameter></｜DSML｜ invoke>"))

;; lenient aliases + <edn> wrapper the model has been observed to emit
(check "edn: <edn> wrapper with :tool alias"
       {:tool-call {:name "board" :args {}}}
       (llm/content->action "<edn>{:tool :board}</edn>"))
(check "edn: prose + <edn> block"
       {:tool-call {:name "board" :args {}}}
       (llm/content->action "I'll look first.\n<edn>{:tool :board}</edn>"))
(check "edn: :tool-call as bare name string"
       {:tool-call {:name "board" :args {}}}
       (llm/content->action "{:tool-call \"board\"}"))
(check "edn: :tool alias with args"
       {:tool-call {:name "task_list" :args {:status "open"}}}
       (llm/content->action "{:tool :task_list :args {:status \"open\"}}"))

(if-not (real-key-present?)
  (do (println "SKIP functional: no DeepSeek key resolvable") (System/exit 0))
  (do
    ;; 1. default provider, key resolved from ~/.nodecode/auth.json
    (let [p (c/resolve-provider (c/load-config {}) :deepseek {})
          action (call! (llm/provider p))]
      (check "default provider model is deepseek-flash" "deepseek-flash" (:model p))
      (check "default provider returns a done action" {:done :ok} action))

    ;; 2. token-helper key: api_key is "!python3 -c '...'" printing the real key.
    ;;    A distinct alias (:helper) makes the "!" command the only key source.
    (let [q (str (char 34))  ; one double-quote, built without a backslash escape
          auth-path (str (System/getProperty "user.home") "/.nodecode/auth.json")
          py (str "import json; print(json.load(open(" q auth-path q "))[" q "api_keys" q "][" q "deepseek" q "][" q "key" q "])")
          helper-cmd (str "!python3 -c '" py "'")
          f (tmpfile {:providers {:helper {:base_url "https://api.deepseek.com/v1"
                                           :model "deepseek-flash"
                                           :api_key helper-cmd}}})
          cfg (c/load-config {:settings-file f :auth-file nf})
          p (c/resolve-provider cfg :helper {:settings-file f :auth-file nf})
          action (call! (llm/provider p))]
      (check "token helper resolves a non-trivial key" true
             (and (:api-key p) (> (count (:api-key p)) 10)))
      (check "token-helper provider returns a done action" {:done :ok} action))

    ;; 3. the deep-thinker rung: orchestrator resolves deepseek-v4-pro at effort
    ;;    max (via the reasoning field) and returns a valid action over the API.
    (let [orch (c/resolve-role (c/load-config {}) :orchestrator {})
          action (call! (llm/provider orch))]
      (check "orchestrator model is deepseek-v4-pro" "deepseek-v4-pro" (:model orch))
      (check "orchestrator reasoning is max" "max" (:reasoning orch))
      (check "orchestrator returns a done action" {:done :ok} action))

    ;; 4. native function calling: send tools, read message.tool_calls
    (let [p (c/resolve-provider (c/load-config {}) :deepseek {})
          action (llm/completion->action
                  (assoc p :tools (tools/openai-tools))
                  [{:role "system" :content "You are faraday's functional test. Use the tools."}
                   {:role "user" :content "Call the board tool."}])]
      (check "native tools return a structured tool call" true
             (boolean (or (:tool-call action) (:tool-calls action)))))

    (println "ALL FUNCTIONAL TESTS PASSED")))
(shutdown-agents)
