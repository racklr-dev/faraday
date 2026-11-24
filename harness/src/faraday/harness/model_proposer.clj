;; faraday.harness.model-proposer — the model-driven self-improvement proposer.
;; (fn [manifest outcome] -> candidate-manifest | nil). It posts the manifest and
;; the observed run outcome to a chat-completions model over HTTPS (curl, because
;; the jolt runtime has no JVM TLS) and returns the model's revised manifest, or
;; nil when the model answers :no-proposal. Config: opts > FARADAY_LLM_* env >
;; deepseek defaults; the :http fn is a test seam. It throws on missing config or
;; transport/model failure — a silent :no-proposal is reserved for a model that
;; genuinely declines to propose.
(ns faraday.harness.model-proposer
  (:require [faraday.harness :as h]
            [faraday.harness.config :as config]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn resolve-config
  "opts {:base-url :api-key :model} > FARADAY_LLM_BASE_URL/API_KEY/MODEL env >
  settings.json (proposer.* then the legacy llm.* shape) > ~/.nodecode/auth.json
  > deepseek defaults. Full resolution lives in faraday.harness.config/
  resolve-proposer. Returns the resolved config map; :api-key may be nil, in
  which case the proposer throws on use."
  [opts]
  (config/resolve-proposer (config/load-config opts) opts))

(defn- system-prompt []
  (str "You are faraday's self-improvement proposer. The agent loop is data: an EDN manifest. "
       "You are given the current manifest and the observed outcome of running it. "
       "Propose a revised manifest (EDN) that fixes the failure, keeping :v and :loop-id stable unless a change is required. "
       "Respond with ONLY the EDN map of the revised manifest, or the EDN keyword :no-proposal if you cannot improve it. "
       "No markdown fences, no prose, no commentary."))

(defn- user-prompt [manifest outcome]
  (str "Current manifest:\n" (pr-str manifest)
       "\n\nObserved outcome:\n" (pr-str outcome)))

(defn- chat-messages [manifest outcome]
  [{:role "system" :content (system-prompt)}
   {:role "user" :content (user-prompt manifest outcome)}])

(defn- curl-http
  "The real transport: POST chat/completions over curl. cfg is {:base-url
  :api-key :model}. Returns the raw response body string; throws on a non-zero
  curl exit or timeout. The Authorization header never appears in the error."
  [cfg messages]
  (let [body (json/write-str {:model (:model cfg)
                              :messages messages
                              :temperature 0})
        argv ["curl" "-fsS" "-m" "120"
              (str (:base-url cfg) "/chat/completions")
              "-H" "Content-Type: application/json"
              "-H" (str "Authorization: Bearer " (:api-key cfg))
              "--data-binary" body]
        r (h/run-command argv 120000)]
    (when (or (:timeout? r) (not= 0 (:exit r)))
      (throw (ex-info "model-proposer curl failed"
                      {:exit (:exit r)
                       :timeout? (:timeout? r)
                       :out (str/trim (:out r))})))
    (:out r)))

(defn- strip-fences [s]
  (-> (str/trim s)
      (str/replace #"^```[a-zA-Z0-9_-]*\s*\n" "")
      (str/replace #"\n```\s*$" "")
      str/trim))

(defn- parse-content [content]
  (let [content (strip-fences (str content))]
    (when (str/blank? content)
      (throw (ex-info "model-proposer returned empty content" {})))
    (let [parsed (try (edn/read-string content)
                      (catch Exception e
                        (throw (ex-info "model-proposer returned unreadable EDN"
                                        {:content content :cause (ex-message e)}))))]
      (cond
        (= :no-proposal parsed) nil
        (map? parsed) parsed
        :else (throw (ex-info "model-proposer returned non-manifest EDN"
                              {:type (str (type parsed))}))))))

(defn model-proposer
  "Factory: (fn [manifest outcome] -> candidate-manifest | nil).
  opts: :base-url, :api-key, :model, :http (fn [cfg messages] -> response body
  string; the test seam). The default :http posts over curl. Throws on a missing
  :api-key or a transport/model failure; returns nil only on :no-proposal."
  [opts]
  (let [cfg (resolve-config opts)
        http (or (:http opts) curl-http)]
    (when-not (:api-key cfg)
      (throw (ex-info (str "no LLM API key for the model proposer; "
                           "set FARADAY_LLM_API_KEY or DEEPSEEK_API_KEY")
                      {})))
    (fn [manifest outcome]
      (let [resp (json/read-str (http cfg (chat-messages manifest outcome)) :key-fn keyword)
            content (get-in resp [:choices 0 :message :content])]
        (when-not content
          (throw (ex-info "model-proposer response had no choices[0].message.content"
                          {:response resp})))
        (parse-content content)))))
