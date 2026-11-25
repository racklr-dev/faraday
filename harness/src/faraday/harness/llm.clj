;; faraday.harness.llm — a shared OpenAI-completions provider for the harness.
;; One transport for chat, the model-proposer, and the agent loop's model step:
;; curl (jolt is a native Chez binary — no JVM TLS), a chat/completions POST,
;; and EDN-parsing of the assistant content into an action map. The :http fn
;; is the test seam everywhere, so tests never hit the network.
(ns faraday.harness.llm
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [faraday.harness :as h]))

(def default-system
  "You are faraday's agent loop. Decide the next action.")

(defn effort-level
  "DeepSeek's effort knob, mapped from faraday's :reasoning role field.
  Returns nil for anything that is not a supported level, so a role without an
  explicit reasoning uses the provider default (high)."
  [reasoning]
  (let [lvl (str reasoning)]
    (when (contains? #{"low" "high" "max"} lvl) lvl)))

(defn request-body
  "The chat/completions JSON body for cfg+messages. cfg's :reasoning
  (low/high/max) becomes the DeepSeek :effort field; cfg's :tools becomes the
  native function-calling tools array (with :tool-choice)."
  [cfg messages]
  (let [effort (effort-level (:reasoning cfg))
        tools (:tools cfg)]
    (cond-> {:model (:model cfg)
             :messages messages
             :temperature (or (:temperature cfg) 0)}
      effort (assoc :effort effort)
      tools (assoc :tools tools :tool-choice (or (:tool-choice cfg) "auto")))))

(declare curl-http content->action)

(defn response-usage
  "Token usage from a parsed chat/completions response, restricted to the token
  fields: {:prompt_tokens .. :completion_tokens .. :total_tokens ..}. nil when
  the provider omitted :usage."
  [resp]
  (let [u (:usage resp)]
    (when (map? u)
      (not-empty (select-keys u [:prompt_tokens :completion_tokens :total_tokens])))))

(defn completion->result
  "Call chat/completions with cfg (which may carry :tools) and return
  {:action .. :usage ..} from the one response. :action is the canonical action
  — {:tool-call ..} / {:tool-calls [..]} from native message.tool_calls, or a
  parsed EDN action / plain-text string from message.content, or nil when
  neither is present — and :usage is response-usage. Splitting the two out of
  one HTTP call lets a cost-tracking caller avoid re-calling the model."
  [cfg messages]
  (let [body (curl-http cfg messages)
        resp (json/read-str body :key-fn keyword)
        msg (get-in resp [:choices 0 :message])
        tcs (seq (:tool_calls msg))
        action (cond
                 tcs
                 (let [calls (mapv (fn [tc]
                                     {:name (get-in tc [:function :name])
                                      :args (try (json/read-str (get-in tc [:function :arguments]) :key-fn keyword)
                                                 (catch Exception _ {}))})
                                   tcs)]
                   (if (= 1 (count calls)) {:tool-call (first calls)} {:tool-calls calls}))

                 (seq (:content msg))
                 (let [c (:content msg)
                       a (try (content->action c) (catch Exception _ nil))]
                   (or a c))

                 :else nil)]
    {:action action :usage (response-usage resp)}))

(defn completion->action
  "The action half of completion->result (the historical contract: callers that
  do not track usage keep the same return value)."
  [cfg messages]
  (:action (completion->result cfg messages)))

(defn add-usage
  "Accumulate one response's :usage into a running token total. Returns a map
  with :prompt_tokens/:completion_tokens/:total_tokens summed, and :requests
  incremented even when `usage` is nil (a billed call with no usage data still
  counts as a request)."
  [total usage]
  (merge-with + (assoc total :requests (inc (or (:requests total) 0)))
              (or usage {})))

(defn curl-http
  "The real transport: POST chat/completions over curl. cfg is {:base-url
  :api-key :model :temperature :reasoning}. Returns the raw response body
  string; throws on a non-zero curl exit or timeout. The Authorization header
  never appears in the error."
  [cfg messages]
  (let [body (json/write-str (request-body cfg messages))
        argv ["curl" "-fsS" "-m" "120"
              (str (:base-url cfg) "/chat/completions")
              "-H" "Content-Type: application/json"
              "-H" (str "Authorization: Bearer " (:api-key cfg))
              "--data-binary" body]
        r (h/run-command argv 120000)]
    (when (or (:timeout? r) (not= 0 (:exit r)))
      (throw (ex-info "llm chat/completions failed"
                      {:exit (:exit r)
                       :timeout? (:timeout? r)
                       :out (str/trim (:out r))})))
    (:out r)))

(defn strip-fences [s]
  (-> (str/trim (str s))
      (str/replace #"^```[a-zA-Z0-9_-]*\s*\n" "")
      (str/replace #"\n```\s*$" "")
      str/trim))

(defn- dsml-param-value
  "Read a DSML parameter body as EDN; falls back to the raw string when it is
  not EDN (plain text args)."
  [s]
  (let [s (str/trim s)]
    (try (edn/read-string s) (catch Exception _ s))))

(defn- dsml-tool-calls
  "Parse DeepSeek DSML tool calls into [{:name :args} ...]. Handles both
  self-closing <｜DSML｜invoke name=\"x\" /> and open blocks with nested
  <｜DSML｜parameter name=\"k\">v</｜DSML｜parameter> children."
  [content]
  (let [self-re #"<｜DSML｜\s*invoke\s+name=\"([^\"]+)\"\s*/>"
        open-re #"(?s)<｜DSML｜\s*invoke\s+name=\"([^\"]+)\"\s*>(.*?)</｜DSML｜\s*invoke\s*>"
        param-re #"(?s)<｜DSML｜\s*parameter\s+name=\"([^\"]+)\"\s*>(.*?)</｜DSML｜\s*parameter\s*>"
        selfs (map (fn [[_ n]] {:name n :args {}}) (re-seq self-re content))
        opens (map (fn [[_ n body]]
                     {:name n
                      :args (into {} (map (fn [[_ k v]] [(keyword k) (dsml-param-value v)])
                                          (re-seq param-re body)))})
                   (re-seq open-re content))]
    (vec (concat selfs opens))))

(defn- tool-name
  "A tool name as a string, from a keyword/string/symbol."
  [v]
  (when v (if (keyword? v) (name v) (str v))))

(defn- normalize-tool-call
  "Coerce one tool-call spec (canonical {:name :args}, a bare name string, or
  the lenient {:tool ..} / {:tool-call \"name\"} shapes) into {:name :args}."
  [x]
  (cond
    (or (string? x) (keyword? x))
    {:name (tool-name x) :args {}}

    (map? x)
    (let [name (tool-name (or (:name x)
                              (:tool x)
                              (when (or (string? (:tool-call x)) (keyword? (:tool-call x)))
                                (:tool-call x))))
          args (or (:args x) (:arguments x) {})]
      (when name {:name name :args (if (map? args) args {})}))))

(defn- normalize-action
  "Coerce a parsed EDN map into the canonical action contract: {:tool-call ..}
  for one call, {:tool-calls [..]} for several, or {:done ..}. Returns nil for
  a map that is not an action."
  [a]
  (when (map? a)
    (cond
      (:done a) {:done (:done a)}
      (:tool-calls a) {:tool-calls (vec (keep normalize-tool-call (:tool-calls a)))}
      (:tool-call a) (when-let [tc (normalize-tool-call (:tool-call a))] {:tool-call tc})
      (:tool a) (when-let [tc (normalize-tool-call {:name (:tool a) :args (:args a)})] {:tool-call tc})
      :else nil)))

(defn- extract-edn
  "The EDN to read out of possibly-noisy content: the inside of an
  <edn>...</edn> block, else the first bare {...} map, else the content itself."
  [content]
  (or (when-let [m (re-find #"(?s)<edn>\s*(.*?)\s*</edn>" content)] (second m))
      (when-let [m (re-find #"(?s)\{.*\}" content)] m)
      content))

(defn content->action
  "Parse an assistant content string into a canonical action map. Accepts, in
  order: DeepSeek DSML (<｜DSML｜invoke ...>), EDN inside <edn>...</edn> or a bare
  EDN map (possibly surrounded by prose), with lenient aliases {:tool name},
  {:tool-call name}, and a bare name. Throws on blank or unreadable content,
  which faraday.agent.model treats as a failed call and repairs/escalates past."
  [content]
  (let [content (strip-fences content)]
    (when (str/blank? content)
      (throw (ex-info "llm returned empty content" {})))
    (cond
      (str/includes? content "｜DSML｜")
      (let [calls (dsml-tool-calls content)]
        (when (seq calls)
          (if (= 1 (count calls))
            {:tool-call (first calls)}
            {:tool-calls calls})))

      :else
      (normalize-action (edn/read-string (extract-edn content))))))

(defn request->messages
  "Turn a model-agent provider request {:role :context :guidance :system} into
  chat messages. A request that already carries :messages passes through.
  Otherwise: one system prompt (request :system, cfg :system/:prompt, or the
  default) plus one user message carrying the turn context and any guidance."
  [request cfg]
  (if-let [msgs (:messages request)]
    msgs
    (let [system (or (:system request) (:system cfg) (:prompt cfg) default-system)
          ctx (:context request)
          guidance (:guidance request)]
      [{:role "system" :content system}
       {:role "user"
        :content (str "Turn context:\n" (pr-str ctx)
                      (when (seq guidance)
                        (str "\n\nGuidance: " guidance))
                      "\n\nRespond with exactly one EDN map: either "
                      "{:tool-call {:name .. :args {:argv [..]}}} or {:done ..}.")}])))

(defn provider
  "Build (fn [request] -> action-map) from a resolved provider cfg. Calls
  chat/completions and EDN-parses the assistant content into an action map.
  http is (fn [cfg messages] -> response-body-string); nil = curl. Throws on
  transport/model failure (faraday.agent.model catches that and repairs or
  escalates past the failed rung)."
  ([cfg] (provider cfg nil))
  ([cfg http]
   (let [http (or http curl-http)]
     (fn [request]
       ;; per-role overrides (a deep-thinker rung's :model/:reasoning/:temperature)
       (let [cfg (cond-> cfg
                   (:model request) (assoc :model (:model request))
                   (:temperature request) (assoc :temperature (:temperature request))
                   (:reasoning request) (assoc :reasoning (:reasoning request)))
             messages (request->messages request cfg)
             body (http cfg messages)
             resp (json/read-str body :key-fn keyword)
             content (get-in resp [:choices 0 :message :content])]
         (when-not content
           (throw (ex-info "llm response had no choices[0].message.content"
                           {:response resp})))
         (content->action content))))))
