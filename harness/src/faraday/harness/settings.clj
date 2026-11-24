;; faraday.harness.settings — user-level settings for the faraday CLI.
;; The single resolver for the model-proposer LLM config (the self-improvement
;; proposer seam). Precedence, highest first:
;;   opts (CLI flags / test seam) > FARADAY_LLM_* / DEEPSEEK_API_KEY env
;;   > ~/.faraday/settings.json (llm.base_url/api_key/model, or the auth.json
;;     shape api_keys.deepseek.key) > ~/.nodecode/auth.json
;;     (api_keys.deepseek.key) > deepseek defaults.
;; File locations are overridable via :settings-file/:auth-file opts and the
;; FARADAY_SETTINGS / FARADAY_NODECODE_AUTH env vars, so tests stay hermetic
;; and never leak the operator's real key into assertions.
(ns faraday.harness.settings
  (:require [clojure.data.json :as json]))

(defn- env [k] (not-empty (System/getenv k)))

(defn settings-file
  "Path to the faraday settings file: FARADAY_SETTINGS env, else
  ~/.faraday/settings.json."
  []
  (or (env "FARADAY_SETTINGS")
      (str (System/getProperty "user.home") "/.faraday/settings.json")))

(defn nodecode-auth-file
  "Path to nodecode's auth file: FARADAY_NODECODE_AUTH env, else
  ~/.nodecode/auth.json."
  []
  (or (env "FARADAY_NODECODE_AUTH")
      (str (System/getProperty "user.home") "/.nodecode/auth.json")))

(defn read-json-map
  "Read a JSON file into a keyword-keyed map; nil when path is nil, missing,
  or unreadable. Never throws."
  [path]
  (when (and path (string? path))
    (try
      (let [f (java.io.File. path)]
        (when (and (.isFile f) (.canRead f))
          (json/read-str (slurp f) :key-fn keyword)))
      (catch Exception _ nil))))

(defn llm-opts
  "Resolve {:base-url :api-key :model} for the model proposer. Precedence,
  highest first: opts > FARADAY_LLM_* / DEEPSEEK_API_KEY env > settings file
  (llm.* or api_keys.deepseek.key) > nodecode auth file > deepseek defaults.
  :settings-file and :auth-file in opts (or their env vars) override the
  default file locations; :api-key may come back nil, which callers turn into
  a clean error."
  [opts]
  (let [settings (read-json-map (or (:settings-file opts) (settings-file)))
        auth (read-json-map (or (:auth-file opts) (nodecode-auth-file)))
        llm (or (:llm settings) {})
        key-from-files (or (:api_key llm) (:api-key llm)
                           (get-in settings [:api_keys :deepseek :key])
                           (get-in auth [:api_keys :deepseek :key]))]
    {:base-url (or (:base-url opts)
                   (env "FARADAY_LLM_BASE_URL")
                   (:base_url llm) (:base-url llm)
                   "https://api.deepseek.com/v1")
     :api-key (or (:api-key opts)
                  (env "FARADAY_LLM_API_KEY")
                  (env "FARADAY_LLM_KEY")
                  (env "DEEPSEEK_API_KEY")
                  key-from-files)
     :model (or (:model opts)
                (env "FARADAY_LLM_MODEL")
                (:model llm)
                "deepseek-flash")}))
