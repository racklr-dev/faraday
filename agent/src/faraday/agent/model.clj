;; faraday-agent model step-fn: a robust, provider-routed model step.
;; A model-agent is a step fn interchangeable with scripted-agent: it turns
;; the loop context into {:tool-call ..} or {:done ..} by calling a model
;; through a provider fn. On an invalid action it repairs (re-calls with
;; repair guidance), then escalates to a stronger provider role, then
;; circuit-breaks so a weak model cannot spin the loop forever.
(ns faraday.agent.model
  "Robust model step-fn for the jolt control plane.

  A model-agent is a step fn interchangeable with scripted-agent: it turns the
  loop context into {:tool-call ..} or {:done ..} by calling a model through a
  provider fn. On an invalid action it repairs (re-calls with repair guidance),
  then escalates to a stronger provider role, then circuit-breaks so a weak
  model cannot spin the loop forever. Provider alias/role config is the dirge
  routing shape: {alias provider-fn} + {position alias}, realized by
  routed-provider.")

(def default-role-config
  {:default :default
   :repair :repair
   :escalate :escalate})

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
    :role-config   {policy-position alias}; default {:default :default
                   :repair :repair :escalate :escalate}.
    :max-repairs         repairs before escalation (default 2)
    :max-escalations     escalations before circuit-break (default 1)
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
                         "{:tool-call {:name .. :args {:argv [..]}}} or {:done ..}.")]
    (when-not (ifn? provider)
      (fail! "model-agent requires a :provider fn or :providers table" {:opts opts}))
    (fn [ctx]
      (if (:open? @breaker)
        {:done {:error :circuit-open}}
        (let [call (fn [alias g]
                     (try
                       (let [resp (provider {:role alias :context ctx :guidance g})
                             action (parse resp)]
                         (when (and (map? action) (validate action)) action))
                       (catch Exception _ nil)))
              repairs (fn []
                        (loop [left max-repairs]
                          (when (pos? left)
                            (or (call (:repair roles) repair-note)
                                (recur (dec left))))))
              escalations (fn []
                            (loop [left max-escalations]
                              (when (pos? left)
                                (or (call (:escalate roles)
                                          "Repair failed; return a valid action.")
                                    (recur (dec left))))))
              action (or (call (:default roles) nil)
                         (repairs)
                         (escalations))]
          (if action
            (do (swap! breaker assoc :failures 0)
                action)
            (let [n (inc (:failures @breaker))]
              (swap! breaker assoc :failures n)
              (when (>= n breaker-threshold)
                (swap! breaker assoc :open? true))
              {:done {:error :step-invalid :failures n}})))))))
