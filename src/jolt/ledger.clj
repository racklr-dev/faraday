;; jolt-ledger: durable idempotent thread state + processed-ID dedup.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.ledger
  "Durable, idempotent thread state for the jolt control plane.

  The ledger is the single writer for Thread Messages (jolt-contracts
  SPEC.md §1): it assigns each message a per-thread monotonic seq,
  dedups on msg-id, and answers history/processed queries. Storage is
  delegated to jolt-lang/db.")

(defn new-ledger
  "Open (or create) a ledger rooted at opts, a map with at least :path.
  Returns an opaque ledger handle."
  [opts]
  (throw (ex-info "not implemented: new-ledger" {:opts opts})))

(defn append!
  "Append one Thread Message map (SPEC.md §1) to the ledger.
  Assigns the next :seq for its :thread-id; if a message with the same
  :msg-id was already appended, returns the original result and does not
  duplicate it. Returns {:seq n, :deduped? bool}."
  [ledger msg]
  (throw (ex-info "not implemented: append!" {:msg msg})))

(defn history
  "Return the messages of thread-id in :seq order, as a vector of maps."
  [ledger thread-id]
  (throw (ex-info "not implemented: history" {:thread-id thread-id})))

(defn processed?
  "True iff a message with msg-id has already been appended (idempotency probe)."
  [ledger msg-id]
  (throw (ex-info "not implemented: processed?" {:msg-id msg-id})))

(defn last-seq
  "The highest :seq seen for thread-id, or nil if none."
  [ledger thread-id]
  (throw (ex-info "not implemented: last-seq" {:thread-id thread-id})))

(defn close!
  "Flush and close the ledger."
  [ledger]
  (throw (ex-info "not implemented: close!" {})))
