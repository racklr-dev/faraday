;; jolt-fanout: task fanout runtime.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.fanout
  "Task fanout runtime for the jolt control plane.

  A runtime emits Thread Messages (jolt-contracts SPEC.md §1) into a
  jolt-ledger, with an optional cedar policy hook that can veto an emit
  before it is appended. Emitted messages carry the standard kinds: spawn,
  inherit, report, yield, user-input, system.")

(defn new-runtime
  "Open a fanout runtime over opts, a map with :ledger (jolt-ledger handle)
  and optionally :memo (jolt-memo handle) and :policy-hook (fn msg -> truthy
  to permit, nil = always permit). Returns an opaque runtime handle."
  [opts]
  (throw (ex-info "not implemented: new-runtime" {:opts opts})))

(defn spawn
  "Emit a spawn Thread Message for spec (a Sandbox Request or tool spec) under
  parent-id (nil at the root), with an optional memo-key. Returns the appended
  message map."
  [rt parent-id spec memo-key]
  (throw (ex-info "not implemented: spawn" {:parent-id parent-id :spec spec :memo-key memo-key})))

(defn inherit
  "Emit an inherit Thread Message that continues from-thread-id at snapshot-ref.
  Returns the appended message map."
  [rt from-thread-id snapshot-ref]
  (throw (ex-info "not implemented: inherit" {:from-thread-id from-thread-id :snapshot-ref snapshot-ref})))

(defn report
  "Emit a report Thread Message for thread-id with status (ok|error|cancelled),
  an optional result, and optional display segments. Returns the appended
  message map."
  [rt thread-id status result segments]
  (throw (ex-info "not implemented: report" {:thread-id thread-id :status status})))

(defn yield
  "Emit a yield (hibernate-to-data) Thread Message for thread-id with state-hash
  and snapshot-ref. Returns the appended message map."
  [rt thread-id state-hash snapshot-ref]
  (throw (ex-info "not implemented: yield" {:thread-id thread-id :state-hash state-hash :snapshot-ref snapshot-ref})))

(defn emit
  "Append an already-shaped Thread Message map to the ledger (after the policy
  hook, if any, permits it). Returns the appended message map."
  [rt msg]
  (throw (ex-info "not implemented: emit" {:msg msg})))

(defn close!
  "Close the runtime (does not close the ledger it was given)."
  [rt]
  (throw (ex-info "not implemented: close!" {})))
