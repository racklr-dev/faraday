;; faraday-agent model step-fn: a robust, provider-routed model step.
;; A model-agent is a step fn interchangeable with scripted-agent: it turns
;; the loop context into {:tool-call ..} or {:done ..} by calling a model
;; through a provider fn. On an invalid action it repairs (re-calls with
;; repair guidance), then escalates to a stronger provider role, then
;; circuit-breaks so a weak model cannot spin the loop forever.
(ns faraday.agent.model
  "Robust model step-fn for a jolt-based control plane.

  A model-agent is a step fn interchangeable with scripted-agent: it turns the
  loop context into {:tool-call ..} or {:done ..} by calling a model through a
  provider fn. On an invalid action it repairs (re-calls with repair guidance),
  then escalates to a stronger provider role, then circuit-breaks so a weak
  model cannot spin the loop forever. Provider alias/role config is the dirge
  routing shape: {alias provider-fn} + {position alias-or-role-map}, realized
  by routed-provider (a map value carries per-role :model/:reasoning/
  :temperature/:system overrides merged into the request).")

(def default-role-config
  {:default :default
   :repair :repair
   :escalate :escalate})

(def default-escalation-ladder
  "Rungs tried in order after the :default role fails; :attempts is per rung.
  The :orchestrator rung is the deep-thinker escalation — include it only when
  a role/agent of that name is configured, or model-agent skips it because its
  alias has no provider."
  [{:role :repair :attempts 2}
   {:role :escalate :attempts 1}])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.model/error true))))

(defn valid-action?
  "True when action is a shape run! accepts: a map with exactly one of :done
  or :tool-call, and a tool-call whose name has no whitespace and whose args
  carry a non-empty :argv vector (the same check faraday-rap enforces)."
  [action]
  (boolean
   (and (map? action)
        (cond
         (contains? action :done) true
         (contains? action :tool-call)
         (let [call (:tool-call action)]
           (and (map? call)
                (string? (:name call))
                (not (re-find #"\s" (:name call)))
                (map? (:args call))
                (vector? (get-in call [:args :argv]))
                (seq (get-in call [:args :argv]))))
         :else false))))

(defn routed-provider
  "Realize provider alias/role config as one provider fn. providers is
  {alias (fn request -> response)}; roles is {policy-position alias} (the
  role-config model-agent reads). The returned fn routes by the request's
  :role alias, which is the dirge routing shape expressed as data + fns."
  [providers roles]
  (fn [request]
    (let [alias (:role request)
          p (get providers alias)]
      (when-not (ifn? p)
        (throw (ex-info (str "no provider for role alias " (pr-str alias))
                        {:role alias :providers (set (keys providers))})))
      (p request))))

(defn model-agent
  "A robust model step-fn, interchangeable with scripted-agent.

  opts:
    :provider      (fn request -> response) where request is {:role alias
                   :context ctx :guidance s} and response is an action map
                   (or whatever :parse turns into one). Required unless
                   :providers is given.
    :providers     {alias (fn request -> response)}; combined with
                   :role-config via routed-provider.
    :role-config   {policy-position alias-or-role-map}; default {:default
                   :default :repair :repair :escalate :escalate}. A map value
                   is {provider alias, model, reasoning, temperature, system}
                   merged into the request for that rung.
    :max-repairs         repairs before escalation (default 2)
    :max-escalations     escalations before circuit-break (default 1)
    :escalation-ladder   [{:role pos :attempts n}] tried in order after
                         :default fails; :role is a role-config position, so
                         :orchestrator is the deep-thinker rung when a
                         provider for it exists (default: [:repair :escalate]
                         with the max-* budgets)
    :breaker-threshold   consecutive full failures that open the circuit
                         (default 3)
    :parse        (fn response -> action) default identity
    :validate     (fn action -> truthy) default valid-action?
    :guidance     string appended to the repair prompt

  Returns (fn [ctx] action)."
  [opts]
  (let [provider (or (:provider opts)
                     (when (:providers opts)
                       (routed-provider (:providers opts) (:role-config opts))))
        roles (merge default-role-config (:role-config opts))
        max-repairs (or (:max-repairs opts) 2)
        max-escalations (or (:max-escalations opts) 1)
        breaker-threshold (or (:breaker-threshold opts) 3)
        parse (or (:parse opts) identity)
        validate (or (:validate opts) valid-action?)
        breaker (atom {:failures 0 :open? false})
        repair-note (str (or (:guidance opts) "")
                         " Return a valid action: exactly one of "
                         "{:tool-call {:name .. :args {:argv [..]}}} or {:done ..}.")
        ladder (or (:escalation-ladder opts)
                   [{:role :repair :attempts max-repairs}
                    {:role :escalate :attempts max-escalations}])
        rung-guidance (fn [pos]
                        (case pos
                          :repair repair-note
                          :escalate "Repair failed; return a valid action."
                          (or (:guidance opts)
                              "Escalation failed; reason deeply, then return a valid action.")))]
    (when-not (ifn? provider)
      (fail! "model-agent requires a :provider fn or :providers table" {:opts opts}))
    (fn [ctx]
      (if (:open? @breaker)
        {:done {:error :circuit-open}}
        (let [resolve-call (fn [pos g]
                             (let [entry (get roles pos pos)
                                   alias (if (map? entry) (:provider entry) entry)
                                   enrich (when (map? entry)
                                            (select-keys entry [:model :reasoning :temperature :system]))
                                   request (cond-> {:role alias :context ctx :guidance g}
                                             enrich (merge enrich))]
                               (try
                                 (let [resp (provider request)
                                       action (parse resp)]
                                   (when (and (map? action) (validate action)) action))
                                 (catch Exception _ nil))))
              try-rung (fn [rung]
                         (let [pos (:role rung)
                               g (or (:guidance rung) (rung-guidance pos))]
                           (loop [left (or (:attempts rung) 1)]
                             (when (pos? left)
                               (or (resolve-call pos g)
                                   (recur (dec left)))))))
              action (or (resolve-call :default nil)
                         (some try-rung ladder))]
          (if action
            (do (swap! breaker assoc :failures 0)
                action)
            (let [n (inc (:failures @breaker))]
              (swap! breaker assoc :failures n)
              (when (>= n breaker-threshold)
                (swap! breaker assoc :open? true))
              {:done {:error :step-invalid :failures n}})))))))
