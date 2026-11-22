;; jolt-memo: content-addressed result cache.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.memo
  "Content-addressed result cache for the jolt control plane.

  A memo maps the canonical content hash of a value to that value, so
  identical inputs produce one stored copy. jolt-fanout uses it behind a
  Thread Message's optional memo-key (jolt-contracts SPEC.md §1) to skip
  recomputing a spawn result. Keys are opaque hex strings; callers must not
  parse structure out of them."
  (:refer-clojure :exclude [get contains?]))

(defn new-memo
  "Open (or create) a memo rooted at opts, a map with at least :path.
  Returns an opaque memo handle."
  [opts]
  (throw (ex-info "not implemented: new-memo" {:opts opts})))

(defn key-of
  "Return the content-hash key for value. Pure: does not store anything.
  Identical values hash to the same key."
  [memo value]
  (throw (ex-info "not implemented: key-of" {:value value})))

(defn put!
  "Store value under its content-hash key. Returns the key. Idempotent:
  putting an already-present value is a no-op that returns its key."
  [memo value]
  (throw (ex-info "not implemented: put!" {:value value})))

(defn get
  "Return the value stored under key, or nil if absent. Callers that must
  distinguish a stored nil use contains?."
  [memo key]
  (throw (ex-info "not implemented: get" {:key key})))

(defn contains?
  "True iff key is present."
  [memo key]
  (throw (ex-info "not implemented: contains?" {:key key})))

(defn delete!
  "Remove key. Returns true if it was present, false otherwise."
  [memo key]
  (throw (ex-info "not implemented: delete!" {:key key})))

(defn stats
  "Return {:entries n, :bytes n} for the memo."
  [memo]
  (throw (ex-info "not implemented: stats" {})))

(defn close!
  "Flush and close the memo."
  [memo]
  (throw (ex-info "not implemented: close!" {})))
