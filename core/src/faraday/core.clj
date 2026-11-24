;; faraday-core: control-plane daemon — budget + policy in one gate.
(ns faraday.core
  "Control-plane daemon for a jolt-based plane. One gate that authorizes a sandbox
  run with the Cedar policy engine (jolt.cedar, SPEC section 6) and only then
  admits it under a resource budget (jolt.admission, SPEC section 5).

  gate authorizes with cedar and then admits under the budget. The caller
  releases the permit when the sandbox finishes. A deny, over-budget or
  timeout surfaces as ex-info with :type :policy-deny or :admission-denied."
  (:require [clojure.string :as str]
            [jolt.admission :as adm]
            [jolt.cedar :as cedar]
            [faraday.pool :as pool]
            [faraday.fanout :as fanout]
            [jolt.microvm :as microvm]))

(defrecord Plane [config admission authorizer policy-text entities closed])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.core/error true))))

(defn- check-open! [plane]
  (when (true? (some-> plane :closed deref))
    (fail! "faraday.core is closed" {:type :plane-closed}))
  plane)

(defn new-plane
  "Open a plane over opts. opts: :budget (resource -> amount, required),
  :weights (resource -> weight, optional), :policy-set (SPEC section 6 with
  :policies :schema :entities), :cedar-lib (path to libcedar_policy.so,
  optional). Validates the policy set with cedar at boot; throws on a
  validation error."
  [opts]
  (let [opts (or opts {})
        budget (:budget opts)
        weights (:weights opts)
        policy-set (or (:policy-set opts) {})
        policies (:policies policy-set)
        policy-text (if (string? policies)
                      policies
                      (str/join "\n" (or policies [])))
        entities (or (:entities policy-set) [])
        schema (or (:schema policy-set) {})]
    (when (str/blank? policy-text)
      (fail! ":policy-set :policies must be a non-empty Cedar source (string or vector)"
             {:opts opts}))
    (let [authorizer (cedar/new-authorizer {:lib (:cedar-lib opts)})
          validation (cedar/validate authorizer policy-text schema)]
      (when (or (= "failure" (:type validation))
                (seq (:validation-errors validation)))
        (fail! "policy set fails cedar validation" {:validation validation}))
      (->Plane (assoc opts :policy-text policy-text :entities entities)
               (adm/new-admission {:budget budget :weights weights})
               authorizer policy-text entities (atom false)))))

(defn authorize
  "Run the cedar decision for request. request carries :principal :action
  :resource :context. Returns the keyword-keyed cedar answer."
  [plane request]
  (check-open! plane)
  (cedar/authorized? (:authorizer plane)
                     (select-keys request [:principal :action :resource :context])
                     (:policy-text plane)
                     (vec (concat (or (:entities plane) [])
                                  (or (:request-entities request) [])))))

(defn decide
  "The cedar decision alone: :allow, :deny or :error."
  [plane request]
  (let [ans (authorize plane request)
        d (get-in ans [:response :decision])]
    (if (or (= "failure" (:type ans)) (nil? d))
      :error
      (keyword d))))

(defn admit
  "Acquire a budget permit for request (:owner :cost :priority :timeout-ms).
  Blocks per jolt.admission; throws :admission-denied on deny/timeout."
  [plane request]
  (check-open! plane)
  (adm/acquire! (:admission plane)
                {:owner (:owner request)
                 :cost (:cost request)
                 :priority (or (:priority request) 0)
                 :timeout-ms (or (:timeout-ms request) 0)}))

(defn release
  "Release a budget permit back to the plane."
  [plane permit]
  (check-open! plane)
  (adm/release! (:admission plane) permit))

(defn gate
  "The gate: authorize with cedar, then admit under the budget. Returns
  {:decision :allow :permit {..}}. Throws on deny, over-budget or timeout."
  [plane request]
  (check-open! plane)
  (let [decision (decide plane request)]
    (when-not (= :allow decision)
      (fail! (str "policy denies sandbox run (" (name decision) ")")
             {:type :policy-deny :request-id (:request-id request) :decision decision}))
    {:decision decision
     :permit (admit plane request)}))

(defn stats
  "Budget stats from the admission controller: {:budget .. :used .. :granted ..
  :waiting ..}."
  [plane]
  (check-open! plane)
  (adm/stats (:admission plane)))

(defn close!
  "Close the plane: cedar authorizer and admission controller. Idempotent."
  [plane]
  (when (map? plane)
    (reset! (:closed plane) true)
    (cedar/close! (:authorizer plane))
    (adm/close! (:admission plane)))
  nil)

;; -- runner: wire the plane gate + vm-pool + fanout ---------------------------

(defrecord Runner [plane pool fanout exec-hook egress emit-lock])

(defn- shell-quote
  "Single-quote one argv element for the guest shell."
  [s]
  (let [q "'" dq (str (char 34))]
    (str q (str/replace (str s) q (str q dq q dq q)) q)))

(defn- argv->command
  "Shell-quote a Sandbox Request argv into one command string for microvm/exec."
  [argv]
  (when-not (seq argv)
    (fail! "request :argv must be a non-empty vector" {:argv argv}))
  (str/join " " (map shell-quote argv)))

(defn- ->cedar-egress
  "Normalize a SPEC section 4 Egress Contract to Cedar-safe attribute keys
  (Cedar attribute names are identifiers, so hyphens become underscores)."
  [e]
  (when e
    (->> {:egress_id (:egress-id e)
          :dns (:dns e)
          :allow_cidrs (:allow-cidrs e)
          :deny_cidrs (:deny-cidrs e)
          :host_gateway (:host-gateway e)
          :default (:default e)}
         (remove (fn [[_ v]] (nil? v)))
         (into {}))))

(defn- egress-inline
  "Resolve a request's :egress (inline contract or {:ref id}) against the
  runner's :egress registry; returns the inline contract map or nil."
  [runner request]
  (let [eg (:egress request)
        reg (or (:egress runner) {})]
    (cond
      (nil? eg) nil
      (:ref eg) (or (get reg (:ref eg))
                    (fail! (str "unknown egress ref " (pr-str (:ref eg)))
                           {:type :egress-unknown :ref (:ref eg)}))
      :else eg)))

(defn- gate-request
  "Turn a Sandbox Request (SPEC section 2) into a plane/gate request: the
  Cedar principal/action/resource (with the egress contract attached as the
  resource entity's attributes) plus the admission fields (:owner :cost
  :priority :timeout-ms). Cedar reads resource attributes from the entities
  array, not the request, so the sandbox is a per-request entity here."
  [runner request]
  (let [request-id (or (:request-id request)
                       (fail! "request requires :request-id" {:request request}))
        eg (->cedar-egress (egress-inline runner request))
        attrs (cond-> {}
                (:image request) (assoc :image (:image request))
                eg (assoc :egress eg))]
    {:request-id request-id
     :principal {:type "Agent" :id (or (:owner request) request-id)}
     :action {:type "Action" :id "RunSandbox"}
     :resource {:type "Sandbox" :id request-id}
     :context (cond-> {:request-id request-id}
                (:owner request) (assoc :owner (:owner request)))
     :request-entities [{:uid {:type "Sandbox" :id request-id}
                         :attrs attrs
                         :parents []}]
     :owner (or (:owner request) request-id)
     :cost {:cpu (or (:cpu request) 1) :mem-mb (or (:mem-mb request) 512)}
     :priority (or (:priority request) 0)
     :timeout-ms (or (:timeout-ms request) 0)}))

(defn- default-exec
  "Run a sandbox request's argv on the acquired handle's live VM. Returns
  {:stdout :stderr :exit}."
  [h request]
  (microvm/exec (:vm h)
                (argv->command (:argv request))
                :timeout-ms (or (:timeout-ms request) 300000)))

(defn new-runner
  "Wire a plane, a vm-pool and a fanout runtime into a runner. opts: :plane
  :pool :fanout (required), :exec-hook (fn handle request -> {:exit :stdout
  :stderr}; default microvm/exec over the handle's VM), :egress (egress-id ->
  Egress Contract map, resolved for {:ref id} requests)."
  [opts]
  (let [pl (:plane opts)
        p (:pool opts)
        rt (:fanout opts)]
    (when-not pl (fail! "new-runner requires a :plane" {:opts opts}))
    (when-not p (fail! "new-runner requires a :pool" {:opts opts}))
    (when-not rt (fail! "new-runner requires a :fanout" {:opts opts}))
    (->Runner pl p rt (or (:exec-hook opts) default-exec)
              (or (:egress opts) {}) (Object.))))

(defn run-sandbox!
  "Run one Sandbox Request (SPEC section 2) end to end under the runner: gate
  (Cedar policy + admission budget), spawn a thread, acquire a sandbox, exec
  argv, release the sandbox and the permit, report. Returns a result map
  {:status :ok|:error|:denied ...}; never throws for a policy or admission
  deny."
  [runner request]
  (let [pl (:plane runner)
        p (:pool runner)
        rt (:fanout runner)
        exec (:exec-hook runner)]
    (try
      (let [g (gate pl (gate-request runner request))
            permit (:permit g)]
        (try
          (let [spawn-msg (locking (:emit-lock runner)
                            (fanout/spawn rt (:parent-id request) request (:memo-key request)))
                thread-id (:thread-id spawn-msg)
                h (pool/acquire p request)
                r (exec h request)]
            (pool/release p h)
            (let [status (if (zero? (:exit r)) "ok" "error")
                  report (locking (:emit-lock runner)
                           (fanout/report rt thread-id status
                                          {:exit (:exit r) :stdout (:stdout r) :stderr (:stderr r)}
                                          [{:type "text" :content (or (:stdout r) "")}]))]
              {:status (if (zero? (:exit r)) :ok :error)
               :request-id (:request-id request)
               :thread-id thread-id
               :report report
               :exit (:exit r)
               :stdout (:stdout r)
               :stderr (:stderr r)}))
          (finally
            (release pl permit))))
      (catch Exception e
        (let [d (ex-data e)
              t (:type d)]
          (cond
            (= :policy-deny t)
            {:status :denied :type :policy-deny :decision (:decision d) :request-id (:request-id request)}
            (= :admission-denied t)
            {:status :denied :type :admission-denied :request-id (:request-id request)}
            :else
            {:status :error :type t :request-id (:request-id request) :error (ex-message e)}))))))

(defn run-all!
  "Run N Sandbox Requests concurrently; the admission budget bounds how many
  sandboxes are live at once and the pool bounds warm VMs. Returns a vector of
  run! result maps, one per request."
  [runner requests]
  ;; jolt installs JDK class providers lazily; touch the classes fanout uses
  ;; here, single-threaded, so the futures below do not race on first use.
  (str (java.time.Instant/now))
  (str (java.util.UUID/randomUUID))
  (mapv deref (mapv (fn [r] (future (run-sandbox! runner r))) requests)))

(defn close-runner!
  "Close the runner: pool first, then the plane. The fanout runtime (and its
  ledger) are borrowed and left open."
  [runner]
  (when (map? runner)
    (pool/close! (:pool runner))
    (close! (:plane runner)))
  nil)
