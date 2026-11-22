;; jolt-vm-pool: warm VM pool + snapshot hibernate.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.vm-pool
  "Warm VM pool and snapshot hibernate for the jolt control plane.

  The pool hands out warm microVMs (jolt-microvm) from jolt-oci images,
  reuses released ones, and hibernates idle sandboxes to snapshots that can
  be restored later. A Sandbox Request is the jolt-contracts SPEC.md §2
  shape. An optional admission hook (Budget Permit seam, SPEC.md §5) can gate
  acquire without a contract change.")

(defn new-pool
  "Open (or create) a pool rooted at opts. Required: :image (OCI ref),
  :cache-dir. Optional: :max-warm (int), :admission-hook (fn request -> truthy
  to proceed, nil = always admit). Returns an opaque pool handle."
  [opts]
  (throw (ex-info "not implemented: new-pool" {:opts opts})))

(defn acquire
  "Hand out a warm sandbox for request (a Sandbox Request map, SPEC.md §2),
  booting one if the pool has none warm. If the pool has an admission hook and
  it returns falsy, throws :admission-denied. Returns an opaque sandbox
  handle."
  [pool request]
  (throw (ex-info "not implemented: acquire" {:request request})))

(defn release
  "Return handle to the warm pool, or discard it if the pool is full. Returns
  the pool's new warm count."
  [pool handle]
  (throw (ex-info "not implemented: release" {:handle handle})))

(defn hibernate
  "Suspend handle to a snapshot and return its snapshot-ref (opaque string)."
  [pool handle]
  (throw (ex-info "not implemented: hibernate" {:handle handle})))

(defn restore
  "Resume snapshot-ref into a warm sandbox and return its handle."
  [pool snapshot-ref]
  (throw (ex-info "not implemented: restore" {:snapshot-ref snapshot-ref})))

(defn stats
  "Return {:warm n, :total n, :snapshots n} for the pool."
  [pool]
  (throw (ex-info "not implemented: stats" {})))

(defn close!
  "Shut down every sandbox and close the pool."
  [pool]
  (throw (ex-info "not implemented: close!" {})))
