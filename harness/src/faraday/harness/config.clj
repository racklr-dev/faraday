;; faraday.harness.config — the settings.json surface for the faraday harness.
;; One resolver for the whole harness, shaped like dirge's config.json (and thus
;; like .claude/settings.json / .gemini/settings.json):
;;
;;   providers   — alias -> {provider-type, base-url, api-key|api-key-env,
;;                            model, reasoning, temperature}; api-key (and the
;;                            value of api-key-env) may start with '!' to run a
;;                            token helper, e.g. "!op read op://private/deepseek"
;;   roles       — policy position -> {provider alias, model, reasoning,
;;                            temperature, agent, system}   (dirge's *_provider
;;                            keys, one map instead of seven)
;;   agents      — name -> {prompt|system, provider, model, reasoning,
;;                            temperature, allow-tools, deny-tools, description}
;;   escalation  — {max-repairs, max-escalations, breaker-threshold,
;;                  ladder [repair escalate orchestrator]}
;;   proposer    — {provider, model, base-url} for the self-improvement proposer
;;   chat        — {provider, model, system, wrap} for the chat TUI
;;
;; Resolution precedence (highest first): opts (CLI flags / test seam) > env >
;; project .faraday/settings.json > ~/.faraday/settings.json > ~/.nodecode/
;; auth.json > built-in defaults. File locations are overridable via opts and
;; the FARADAY_SETTINGS / FARADAY_NODECODE_AUTH env vars, so tests stay
;; hermetic.
(ns faraday.harness.config
  (:require [clojure.data.json :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

(defn env [k] (not-empty (System/getenv k)))

;; -- file locations ----------------------------------------------------------

(defn settings-file
  "FARADAY_SETTINGS env, else ~/.faraday/settings.json."
  []
  (or (env "FARADAY_SETTINGS")
      (str (System/getProperty "user.home") "/.faraday/settings.json")))

(defn nodecode-auth-file
  "FARADAY_NODECODE_AUTH env, else ~/.nodecode/auth.json."
  []
  (or (env "FARADAY_NODECODE_AUTH")
      (str (System/getProperty "user.home") "/.nodecode/auth.json")))

(defn project-settings-file
  "The per-project settings file: <root>/.faraday/settings.json (git-ignored,
  like the harness.db). Higher precedence than the user-level file."
  [root]
  (str root "/.faraday/settings.json"))

(defn- json-key
  "Read a JSON key string as a keyword, normalizing underscores to hyphens so
  settings.json can use dirge/OpenAI spellings (base_url, api_key,
  provider_type, max_repairs) and merge cleanly with the hyphenated defaults."
  [k]
  (keyword (str/replace (name k) "_" "-")))

(defn read-json-map
  "Read a JSON file into a keyword-keyed map (keys normalized to hyphens); nil
  when path is nil, missing, or unreadable. Never throws."
  [path]
  (when (and path (string? path))
    (try
      (let [f (java.io.File. path)]
        (when (and (.isFile f) (.canRead f))
          (json/read-str (slurp f) :key-fn json-key)))
      (catch Exception _ nil))))

;; -- merged raw config --------------------------------------------------------

(def builtin-defaults
  {:providers
   {:deepseek {:provider-type "openai-completions"
               :base-url "https://api.deepseek.com/v1"
               :model "deepseek-flash"
               :api-key-env "DEEPSEEK_API_KEY"}
    :deepseek-pro {:provider-type "openai-completions"
                   :base-url "https://api.deepseek.com/v1"
                   :model "deepseek-v4-pro"
                   :api-key-env "DEEPSEEK_API_KEY"}}
   :roles
   {:default {:provider :deepseek}
    :repair {:provider :deepseek}
    :escalate {:provider :deepseek-pro :reasoning "high"}
    :orchestrator {:provider :deepseek-pro :reasoning "max"}
    :critic {:provider :deepseek}
    :review {:provider :deepseek}
    :summarizer {:provider :deepseek}
    :subagent {:provider :deepseek}
    :approval {:provider :deepseek}}
   :agents {}
   :escalation {:max-repairs 2
                :max-escalations 1
                :breaker-threshold 3
                :ladder [:repair :escalate]}
   :proposer {}
   :chat {}})

(defn- deep-merge
  "Merge maps recursively; later wins. nil on either side defers to the other
  side, so an absent settings layer never clobbers the layer under it. Vectors
  and non-maps replace wholesale."
  [a b]
  (cond
    (nil? a) b
    (nil? b) a
    (and (map? a) (map? b)) (merge-with deep-merge a b)
    :else b))

(defn load-config
  "Read and merge the raw config: builtin defaults < user settings < project
  settings. opts may carry :settings-file, :project-root, :auth-file to
  override the locations (tests use these to stay hermetic)."
  [opts]
  (let [user (read-json-map (or (:settings-file opts) (settings-file)))
        proj (read-json-map (or (:project-settings-file opts)
                                (when-let [root (:project-root opts)]
                                  (project-settings-file root))))
        extra (select-keys opts [:providers :roles :agents :escalation
                                 :proposer :chat])]
    (reduce deep-merge builtin-defaults [user proj extra])))

;; -- api-key resolution (never prints the key) --------------------------------

(defn- key-from-files
  "Deepseek-shaped key in the faraday settings or nodecode auth file."
  [settings auth alias]
  (or (get-in settings [:api-keys alias :key])
      (get-in auth [:api-keys alias :key])))

(def ^:private token-helper-key
  "Run a token-helper command (the part after a leading '!') and return its
  first non-blank stdout line as the key. Memoized: each helper command runs
  once per process. Throws (uncached) on non-zero exit or empty output, so a
  broken helper surfaces loudly instead of being sent as a literal key."
  (memoize
   (fn [cmd]
     (let [r (shell/sh "sh" "-c" cmd)]
       (when-not (zero? (:exit r))
         (throw (ex-info "token helper failed"
                         {:cmd cmd :exit (:exit r) :out (str/trim (:out r))})))
       (let [out (first (remove str/blank? (str/split-lines (str (:out r)))))]
         (when-not (seq out)
           (throw (ex-info "token helper produced no key" {:cmd cmd})))
         (str/trim out))))))

(defn- resolve-key-value
  "Resolve a candidate key string: a value starting with '!' is a token-helper
  command whose stdout is the key; anything else passes through unchanged.
  nil stays nil."
  [s]
  (when s
    (let [s (str/trim s)]
      (if (str/starts-with? s "!")
        (token-helper-key (str/trim (subs s 1)))
        s))))

(defn- provider-key
  "Resolve an api key for a provider alias. opts :api-key wins; then
  FARADAY_LLM_API_KEY/KEY and DEEPSEEK_API_KEY; then the provider's own
  :api-key-env; then the provider's inline :api-key; then settings/auth files."
  [alias entry settings auth opts]
  (resolve-key-value
   (or (:api-key opts)
       (env "FARADAY_LLM_API_KEY")
       (env "FARADAY_LLM_KEY")
       (env "DEEPSEEK_API_KEY")
       (when-let [k (:api-key-env entry)] (env (name k)))
       (:api-key entry)
       (key-from-files settings auth alias)
       (key-from-files settings auth :deepseek))))

;; -- providers ----------------------------------------------------------------

(defn resolve-provider
  "Resolve one provider alias into a callable config:
  {:provider alias :base-url :api-key :model :reasoning :temperature
   :provider-type}. opts :base-url/:api-key/:model win; the provider entry in
  the merged config fills the rest; deepseek defaults last."
  [config alias opts]
  (let [alias (if (keyword? alias) alias (keyword alias))
        settings (read-json-map (or (:settings-file opts) (settings-file)))
        auth (read-json-map (or (:auth-file opts) (nodecode-auth-file)))
        entry (or (get-in config [:providers alias]) {})
        base (get-in config [:providers :deepseek])]
    {:provider alias
     :provider-type (or (:provider-type entry) (:provider-type base)
                        "openai-completions")
     :base-url (or (:base-url opts)
                   (env "FARADAY_LLM_BASE_URL")
                   (:base-url entry) (:base_url entry)
                   (:base-url base)
                   "https://api.deepseek.com/v1")
     :api-key (provider-key alias entry settings auth opts)
     :model (or (:model opts)
                (env "FARADAY_LLM_MODEL")
                (:model entry)
                (:model base)
                "deepseek-flash")
     :reasoning (or (:reasoning entry) (:reasoning base))
     :temperature (or (:temperature entry) (:temperature base))}))

;; -- roles ---------------------------------------------------------------------

(def known-roles
  "The policy positions a role entry may occupy. Anything else is still
  accepted (custom roles are allowed), but these are the ones faraday itself
  routes."
  #{:default :repair :escalate :orchestrator :critic :review :summarizer
    :subagent :approval})

(declare resolve-agent)

(defn resolve-role
  "Resolve a policy position into a callable config, like resolve-provider but
  with the role's own :model/:reasoning/:temperature/:agent/:system overlaid
  on its provider. A role may name an :agent, whose prompt becomes the role's
  :system and whose model/reasoning/temperature fill anything the role leaves
  unset. Unknown role falls back to the :default role."
  [config role opts]
  (let [role (if (keyword? role) role (keyword role))
        entry (or (get-in config [:roles role]) {})
        agent (when-let [a (:agent entry)] (resolve-agent config a opts))
        provider-alias (or (:provider entry) (:provider agent) :deepseek)
        p (resolve-provider config provider-alias opts)]
    (merge p
           {:role role
            :model (or (:model entry) (:model agent) (:model p))
            :reasoning (or (:reasoning entry) (:reasoning agent) (:reasoning p))
            :temperature (or (:temperature entry) (:temperature agent) (:temperature p))}
           (when (or (:system entry) (:prompt agent))
             {:system (or (:system entry) (:prompt agent))}))))

;; -- agents ---------------------------------------------------------------------

(defn resolve-agent
  "Resolve a named agent profile: {:name :prompt :provider :base-url :api-key
  :model :reasoning :temperature :allow-tools :deny-tools :description}.
  The profile's :provider is resolved the same way a role's is; :prompt and
  :system are synonyms (a system prompt body)."
  [config name opts]
  (let [name (if (keyword? name) name (keyword name))
        entry (or (get-in config [:agents name]) {})
        provider-alias (or (:provider entry) :deepseek)
        p (resolve-provider config provider-alias opts)]
    (merge p
           {:name name
            :model (or (:model entry) (:model p))
            :reasoning (or (:reasoning entry) (:reasoning p))
            :temperature (or (:temperature entry) (:temperature p))
            :description (:description entry)}
           (when-let [pr (or (:prompt entry) (:system entry))]
             {:prompt pr})
           (when (:allow-tools entry) {:allow-tools (mapv str (:allow-tools entry))})
           (when (:deny-tools entry) {:deny-tools (mapv str (:deny-tools entry))}))))

;; -- escalation ------------------------------------------------------------------

(defn resolve-escalation
  "{:max-repairs :max-escalations :breaker-threshold :ladder [...]}. The
  ladder is a vector of policy positions tried in order after :default fails;
  :orchestrator is the final rung (a deep thinker) and is included only when a
  role or agent of that name is configured."
  [config]
  (let [e (:escalation config)
        ladder (or (:ladder e) (:ladder (:escalation builtin-defaults)))
        ladder (mapv #(if (keyword? %) % (keyword %)) ladder)]
    {:max-repairs (or (:max-repairs e) 2)
     :max-escalations (or (:max-escalations e) 1)
     :breaker-threshold (or (:breaker-threshold e) 3)
     :ladder ladder}))

;; -- proposer / chat ------------------------------------------------------------

(defn resolve-sandbox
  "Resolve the sandbox extension surface: {:apk-packages [...] :nix-flake s}.
  Reads the merged config's :sandbox object (json-key normalized) plus the
  operator's flat spellings sandbox_apk_packages / sandbox_nix_flake."
  [config]
  (let [s (:sandbox config)]
    {:apk-packages (mapv str (or (:apk-packages s) (:sandbox-apk-packages config) []))
     :nix-flake (or (:nix-flake s) (:sandbox-nix-flake config))}))

(defn resolve-proposer
  "Resolve the model-proposer's LLM config: {:base-url :api-key :model ...}.
  Backward compatible with the old ~/.faraday/settings.json llm.* shape: the
  new proposer.{provider,model,base-url} section wins, then llm.*, then the
  provider default."
  [config opts]
  (let [settings (read-json-map (or (:settings-file opts) (settings-file)))
        auth (read-json-map (or (:auth-file opts) (nodecode-auth-file)))
        llm (or (:llm settings) {})
        entry (or (:proposer config) {})
        alias (or (:provider entry) :deepseek)
        p (resolve-provider config alias opts)
        key-from-files (or (:api_key llm) (:api-key llm)
                           (get-in settings [:api-keys :deepseek :key])
                           (get-in auth [:api-keys :deepseek :key]))]
    {:base-url (or (:base-url opts)
                   (env "FARADAY_LLM_BASE_URL")
                   (:base-url entry) (:base_url entry)
                   (:base_url llm) (:base-url llm)
                   (:base-url p))
     :api-key (resolve-key-value
               (or (:api-key opts)
                   (env "FARADAY_LLM_API_KEY")
                   (env "FARADAY_LLM_KEY")
                   (env "DEEPSEEK_API_KEY")
                   (:api-key entry)
                   (:api-key p)
                   key-from-files))
     :model (or (:model opts)
                (env "FARADAY_LLM_MODEL")
                (:model entry)
                (:model llm)
                (:model p))}))

(defn- normalize-context
  "A context value is a string or a sequential collection of strings; return a
  vector of non-blank strings, or nil when empty. The chat injects these into
  its system prompt as operator-provided context — the nodecode/dirge/samizdat
  model: an editable standing context that rides every request."
  [v]
  (let [vs (cond
             (string? v) [v]
             (sequential? v) (vec v)
             :else nil)]
    (not-empty
     (vec (remove (fn [s] (or (nil? s) (str/blank? (str s)))) vs)))))

(defn resolve-chat
  "Resolve the chat TUI's defaults: {:base-url :api-key :model :system :wrap
  :context}. Backward compatible with the old llm.* shape:
  chat.{provider,model,system,wrap,context} wins, then llm.*, then the provider
  default."
  [config opts]
  (let [settings (read-json-map (or (:settings-file opts) (settings-file)))
        llm (or (:llm settings) {})
        entry (or (:chat config) {})
        alias (or (:provider entry) :deepseek)
        p (resolve-provider config alias opts)]
    {:base-url (or (:base-url opts)
                   (env "FARADAY_LLM_BASE_URL")
                   (:base-url entry) (:base_url entry)
                   (:base_url llm) (:base-url llm)
                   (:base-url p))
     :api-key (resolve-key-value
               (or (:api-key opts)
                   (env "FARADAY_LLM_API_KEY")
                   (env "FARADAY_LLM_KEY")
                   (env "DEEPSEEK_API_KEY")
                   (:api-key entry)
                   (:api-key p)
                   (:api_key llm) (:api-key llm)))
     :model (or (:model opts)
                (env "FARADAY_LLM_MODEL")
                (:model entry)
                (:model llm)
                (:model p))
     :system (or (:system entry))
     :context (normalize-context (or (:context opts) (:context entry) (:context config)))
     :wrap (or (:wrap opts) (:wrap entry))}))

;; -- volume mounts (compose-style) --------------------------------------------

(defn expand-home
  "~/x -> the operator's home/x; other paths pass through."
  [p]
  (let [p (str p)]
    (if (or (= "~" p) (str/starts-with? p "~/"))
      (str (System/getProperty "user.home") (subs p 1))
      p)))

(defn parse-mount
  "Parse a compose-style mount string \"host:guest[:mode]\" into
  {:host :guest :readonly?}. mode defaults to ro (read-only); :rw opts into
  writes. Returns nil when host or guest is blank."
  [s]
  (let [[h g mode] (str/split (str s) #":" -1)]
    (when (and (seq h) (seq g))
      {:host (expand-home h)
       :guest g
       :readonly? (not= "rw" (or mode "ro"))})))

(defn resolve-mounts
  "The global volume mounts from config :mounts (a vector of compose-style
  \"host:guest[:ro|rw]\" strings, defaulting to ro). Returns
  [{:host :guest :readonly?} ..]; unparsable entries are dropped."
  [config]
  (keep parse-mount (:mounts config)))

;; -- model-step opts (the bridge to faraday.agent.model) -------------------------

(defn model-step-opts
  "Turn the merged config into faraday.agent.model/model-agent opts.

  provider-builder is (fn [resolved-provider] -> (fn [request] -> response)),
  e.g. faraday.harness.llm/provider. The returned map carries :providers
  {alias provider-fn}, :role-config {position alias-or-map}, :escalation-ladder
  [{:role pos :attempts n}], and the three breaker knobs. The ladder ends at
  :orchestrator only when that role/agent is configured — the deep-thinker
  rung the loop escalates to before it circuit-breaks.

  :reasoning/:model/:temperature overrides travel with the role, not the
  ladder: when a role entry is a map, the ladder resolves it to a request the
  provider-builder can see (see resolve-role)."
  [config opts provider-builder]
  (let [esc (resolve-escalation config)
        roles (:roles config)
        role-config (into {}
                          (map (fn [[pos entry]]
                                 [pos (if (map? entry)
                                        (resolve-role config pos opts)
                                        entry)])
                               roles))
        ladder (mapv (fn [pos]
                       {:role pos
                        :attempts (case pos
                                    :repair (:max-repairs esc)
                                    :escalate (:max-escalations esc)
                                    1)})
                     (filter #(contains? role-config %) (:ladder esc)))
        aliases (set (map (comp :provider second) role-config))]
    {:providers (into {}
                      (for [alias aliases]
                        [alias (provider-builder (resolve-provider config alias opts))]))
     :role-config role-config
     :escalation-ladder ladder
     :max-repairs (:max-repairs esc)
     :max-escalations (:max-escalations esc)
     :breaker-threshold (:breaker-threshold esc)}))

;; -- validation -------------------------------------------------------------------

(defn validate-config
  "Return a vector of problem strings; empty = valid. Checks provider alias
  references from roles/agents/proposer/chat and role/agent references from
  the escalation ladder, without touching any secret."
  [config]
  (let [providers (set (keys (:providers config)))
        roles (set (keys (:roles config)))
        agents (set (keys (:agents config)))
        problems (transient [])]
    (doseq [[pos entry] (:roles config)]
      (when (map? entry)
        (let [p (:provider entry)
              alias (if (keyword? p) p (if p (keyword p) :deepseek))]
          (when-not (contains? providers alias)
            (conj! problems (str "role " (name pos) " references unknown provider " (pr-str alias)))))))
    (doseq [[name entry] (:agents config)]
      (let [p (:provider entry)
            alias (if (keyword? p) p (if p (keyword p) :deepseek))]
        (when-not (contains? providers alias)
          (conj! problems (str "agent " (name name) " references unknown provider " (pr-str alias))))))
    (let [e (:escalation config)]
      (doseq [pos (:ladder e)]
        (let [pos (if (keyword? pos) pos (keyword pos))]
          (when-not (or (contains? roles pos) (contains? agents pos))
            (conj! problems (str "escalation ladder names unknown role/agent " (pr-str pos)))))))
    (doseq [[section {:keys [provider]}] (select-keys config [:proposer :chat])]
      (when (and provider (not (contains? providers (keyword provider))))
        (conj! problems (str (name section) " references unknown provider " (pr-str provider)))))
    (persistent! problems)))
