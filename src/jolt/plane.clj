;; jolt-plane: control-plane daemon — budget + policy in one gate.
(ns jolt.plane
  "Control-plane daemon for the jolt plane. One gate that authorizes a sandbox
  run with the Cedar policy engine (jolt.cedar, SPEC section 6) and only then
  admits it under a resource budget (jolt.admission, SPEC section 5).

  gate authorizes with cedar and then admits under the budget. The caller
  releases the permit when the sandbox finishes. A deny, over-budget or
  timeout surfaces as ex-info with :type :policy-deny or :admission-denied."
  (:require [clojure.string :as str]
            [jolt.admission :as adm]
            [jolt.cedar :as cedar]))

(defrecord Plane [config admission authorizer policy-text entities closed])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :jolt.plane/error true))))

(defn- check-open! [plane]
  (when (true? (some-> plane :closed deref))
    (fail! "jolt.plane is closed" {:type :plane-closed}))
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
                     (:entities plane)))

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
