;; faraday-fanout: task fanout runtime.
;; A runtime shapes Thread Messages (faraday-contracts SPEC.md §1) and appends
;; them to a faraday-ledger, with an optional policy hook that can veto an emit
;; before it is appended. spawn/inherit mint a NEW thread (new thread-id) whose
;; parent-id links it into the tree; report/yield append into an existing
;; thread-id. The runtime never closes the ledger/memo it was given.
(ns faraday.fanout
  "Task fanout runtime for the jolt control plane.

  A runtime emits Thread Messages (faraday-contracts SPEC.md §1) into a
  faraday-ledger, with an optional cedar policy hook that can veto an emit
  before it is appended. Emitted messages carry the standard kinds: spawn,
  inherit, report, yield, user-input, system."
  (:require [faraday.ledger :as ledger]
            [faraday.contracts :as contracts]))

;; An opaque runtime handle: the ledger it writes into, plus the optional
;; memo handle and policy hook. The memo is held for the executor side of a
;; spawn (memo-key in the payload is the seam); this runtime only emits.
(defrecord Runtime [ledger memo policy-hook])

;; -- helpers -----------------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.fanout/error true))))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq v))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(defn- new-id
  "Fresh opaque id with the given prefix (thr_/msg_)."
  [prefix]
  (str prefix (java.util.UUID/randomUUID)))

(defn- now
  "RFC 3339 UTC timestamp for the current instant."
  []
  (str (java.time.Instant/now)))

(def ^:private report-statuses #{"ok" "error" "cancelled"})

(defn- payload-with
  "Start a payload map from base, assoc-ing each [k v] pair whose v is
  non-nil (nil = omitted, keeping the optional-key contract shape)."
  [base pairs]
  (reduce (fn [m [k v]]
            (if (nil? v) m (assoc m k v)))
          base pairs))

;; -- public API --------------------------------------------------------------

(defn new-runtime
  "Open a fanout runtime over opts, a map with :ledger (faraday-ledger handle)
  and optionally :memo (faraday-memo handle) and :policy-hook (fn msg -> truthy
  to permit, nil = always permit). Returns an opaque runtime handle."
  [opts]
  (let [l (:ledger opts)]
    (when-not l
      (fail! "new-runtime requires a :ledger" {:opts opts}))
    (->Runtime l (:memo opts) (:policy-hook opts))))

(defn emit
  "Append an already-shaped Thread Message map to the ledger (after the policy
  hook, if any, permits it). The message is validated against the declared
  Thread Message contract. Returns the appended message map."
  [rt msg]
  (when-not (map? msg)
    (fail! "message must be a map" {:msg msg}))
  (let [msg (contracts/decode! :thread-message msg)]
    (when-let [hook (:policy-hook rt)]
      (when-not (hook msg)
        (fail! "policy vetoed emit" {:type :policy-vetoed :msg msg})))
    (let [{seq' :seq} (ledger/append! (:ledger rt) msg)]
      (assoc msg :seq seq'))))

(defn spawn
  "Emit a spawn Thread Message for spec (a Sandbox Request or tool spec) under
  parent-id (nil at the root), with an optional memo-key. Returns the appended
  message map."
  [rt parent-id spec memo-key]
  (when (nil? spec)
    (fail! "spawn requires a non-nil spec" {:spec spec}))
  (emit rt {:v 1
            :thread-id (new-id "thr_")
            :parent-id parent-id
            :msg-id (new-id "msg_")
            :kind "spawn"
            :ts (now)
            :payload (payload-with {:spec spec} [[:memo-key memo-key]])}))

(defn inherit
  "Emit an inherit Thread Message that continues from-thread-id at snapshot-ref.
  Returns the appended message map."
  [rt from-thread-id snapshot-ref]
  (non-empty-string! from-thread-id :from-thread-id)
  (non-empty-string! snapshot-ref :snapshot-ref)
  (emit rt {:v 1
            :thread-id (new-id "thr_")
            :parent-id from-thread-id
            :msg-id (new-id "msg_")
            :kind "inherit"
            :ts (now)
            :payload {:from-thread-id from-thread-id
                      :snapshot-ref snapshot-ref}}))

(defn report
  "Emit a report Thread Message for thread-id with status (ok|error|cancelled),
  an optional result, and optional display segments. Returns the appended
  message map."
  [rt thread-id status result segments]
  (non-empty-string! thread-id :thread-id)
  (when-not (contains? report-statuses status)
    (fail! "report status must be one of ok|error|cancelled" {:status status}))
  (emit rt {:v 1
            :thread-id thread-id
            :parent-id nil
            :msg-id (new-id "msg_")
            :kind "report"
            :ts (now)
            :payload (payload-with {:status status}
                                   [[:result result] [:segments segments]])}))

(defn yield
  "Emit a yield (hibernate-to-data) Thread Message for thread-id with state-hash
  and snapshot-ref. Returns the appended message map."
  [rt thread-id state-hash snapshot-ref]
  (non-empty-string! thread-id :thread-id)
  (non-empty-string! state-hash :state-hash)
  (non-empty-string! snapshot-ref :snapshot-ref)
  (emit rt {:v 1
            :thread-id thread-id
            :parent-id nil
            :msg-id (new-id "msg_")
            :kind "yield"
            :ts (now)
            :payload {:state-hash state-hash
                      :snapshot-ref snapshot-ref}}))

(defn close!
  "Close the runtime (does not close the ledger it was given)."
  [rt]
  nil)
