;; jolt-vm-pool: warm VM pool + snapshot hibernate.
;; A pool keeps a bounded set of warm microVMs (jolt-microvm) keyed by image,
;; hands them out on acquire (reusing the most recently released warm VM for
;; the requested image, booting one when none is warm), returns them on
;; release (evicting the least-recently-released VM past :max-warm), and
;; suspends/resumes via jolt-microvm snapshots (hibernate/restore). The
;; Sandbox Request is jolt-contracts SPEC.md §2; the pool owns VM *lifecycle*
;; only — running the request argv is the caller's job against the handle's
;; live :vm (see the Sandbox record).
(ns jolt.vm-pool
  "Warm VM pool and snapshot hibernate for the jolt control plane.

  The pool hands out warm microVMs (jolt-microvm) from jolt-oci images,
  reuses released ones (LRU across the warm set), and hibernates idle
  sandboxes to snapshots that can be restored later. A Sandbox Request is
  the jolt-contracts SPEC.md §2 shape. An optional admission hook (Budget
  Permit seam, SPEC.md §5) can gate acquire without a contract change.

  The pool owns VM lifecycle. To run a request's argv, callers execute
  against the handle's live VM: (jolt.microvm/exec (:vm handle) ...)."
  (:require [jolt.microvm :as microvm]
            [clojure.java.shell :as shell])
  (:import [java.io File]))

(defrecord Pool [opts state])

;; An opaque sandbox handle: a live jolt-microvm VM plus the metadata the
;; pool needs to release/hibernate it and to restore its snapshot.
(defrecord Sandbox [id image vm boot-opts])

;; -- validation + ids --------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :jolt.vm-pool/error true))))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq v))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(defn- next-id!
  "Allocate the next id for prefix (sb-/snap-) from the pool's monotonic
  id-seq. Returns \"prefix<n>\"."
  [state prefix]
  (let [s (swap! state update :id-seq inc)]
    (str prefix (:id-seq s))))

(defn- rm-rf!
  "Remove a path recursively if it exists."
  [path]
  (when (.exists (File. path))
    (let [r (shell/sh "rm" "-rf" "--" path)]
      (when-not (zero? (:exit r))
        (fail! "rm -rf failed" {:path path :stderr (:err r)}))))
  nil)

(defn- req-mount->microvm
  "Map a Sandbox Request mount {:host-path :tag :readonly} to a microvm mount
  {:host :guest :tag}, mounting at /mnt/<tag> unless a :guest is given."
  [m]
  (let [tag (:tag m)]
    (when-not (and tag (seq tag))
      (fail! "request mount requires a non-empty :tag" {:mount m}))
    {:host (:host-path m)
     :guest (or (:guest m) (str "/mnt/" tag))
     :tag tag}))

(defn- request->boot-opts
  "The jolt-microvm start! opts a Sandbox Request maps to, merged over the
  pool's defaults. :image comes from the request when present, else the pool."
  [pool request]
  (let [opts (:opts pool)
        bo {:image (or (:image request) (:image opts))
            :cache-dir (:cache-dir opts)
            :project-dir (or (:project-dir opts)
                             (System/getProperty "user.dir"))}
        bo (if (:arch opts) (assoc bo :arch (:arch opts)) bo)
        bo (if (:cpu request) (assoc bo :cpus (:cpu request)) bo)
        bo (if (:mem-mb request) (assoc bo :memory-mib (:mem-mb request)) bo)
        bo (if (seq (:mounts request))
             (assoc bo :mounts (mapv req-mount->microvm (:mounts request)))
             bo)]
    (non-empty-string! (:image bo) :image)
    bo))

;; -- warm-set helpers --------------------------------------------------------

(defn- warm-entry-for
  "The most recently released warm entry for image, or nil."
  [warm image]
  (let [cs (filter #(= image (:image %)) warm)]
    (when (seq cs) (apply max-key :stamp cs))))

(defn- lru-entry
  "The least recently released warm entry, or nil."
  [warm]
  (when (seq warm) (apply min-key :stamp warm)))

(defn- remove-warm
  "Drop the warm entry whose handle has id, keeping order."
  [warm id]
  (vec (remove #(= id (-> % :handle :id)) warm)))

(defn- live-handle?
  "True when handle is currently busy or warm in the pool."
  [pool handle]
  (let [s @(:state pool)
        id (:id handle)]
    (or (contains? (:busy s) id)
        (some #(= id (-> % :handle :id)) (:warm s)))))

(defn- boot!
  "Boot a fresh VM for image+request and register it busy."
  [pool image boot-opts]
  (let [state (:state pool)
        vm (microvm/start! boot-opts)
        h (->Sandbox (next-id! state "sb-") image vm boot-opts)]
    (swap! state (fn [s] (-> s (update :total inc) (assoc-in [:busy (:id h)] h))))
    h))

(defn- evict-over!
  "While the warm set exceeds :max-warm, stop and drop the LRU warm VM."
  [pool]
  (let [state (:state pool)
        max-warm (:max-warm (:opts pool))]
    (loop []
      (when (> (count (:warm @state)) max-warm)
        (let [e (lru-entry (:warm @state))
              id (-> e :handle :id)]
          (try (microvm/stop! (:vm (:handle e))) (catch Exception _ nil))
          (swap! state (fn [s] (-> s
                                   (assoc :warm (remove-warm (:warm s) id))
                                   (update :total dec))))
          (recur))))))

;; -- public API --------------------------------------------------------------

(defn new-pool
  "Open (or create) a pool rooted at opts. Required: :image (OCI ref),
  :cache-dir. Optional: :max-warm (int), :admission-hook (fn request -> truthy
  to proceed, nil = always admit). Returns an opaque pool handle."
  [opts]
  (let [opts (or opts {})
        image (non-empty-string! (:image opts) :image)
        cache-dir (non-empty-string! (:cache-dir opts) :cache-dir)
        max-warm (or (:max-warm opts) 4)]
    (when-not (and (integer? max-warm) (not (neg? max-warm)))
      (fail! ":max-warm must be a non-negative integer" {:max-warm max-warm}))
    (.mkdirs (File. cache-dir))
    (->Pool (assoc opts :image image :cache-dir cache-dir :max-warm max-warm)
            (atom {:warm [] :busy {} :total 0 :snapshots {} :id-seq 0 :stamp 0}))))

(defn acquire
  "Hand out a warm sandbox for request (a Sandbox Request map, SPEC.md §2),
  booting one if the pool has none warm. If the pool has an admission hook and
  it returns falsy, throws :admission-denied. Returns an opaque sandbox
  handle."
  [pool request]
  (let [opts (:opts pool)
        hook (:admission-hook opts)]
    (when-not (map? request)
      (fail! "request must be a map" {:request request}))
    (when (and hook (not (hook request)))
      (fail! "admission denied" {:type :admission-denied :request-id (:request-id request)}))
    (let [boot-opts (request->boot-opts pool request)
          image (:image boot-opts)
          state (:state pool)
          e (warm-entry-for (:warm @state) image)]
      (if e
        (let [h (:handle e)]
          (swap! state (fn [s] (-> s
                                   (assoc :warm (remove-warm (:warm s) (:id h)))
                                   (assoc-in [:busy (:id h)] h))))
          h)
        (boot! pool image boot-opts)))))

(defn release
  "Return handle to the warm pool, or discard it if the pool is full. Returns
  the pool's new warm count."
  [pool handle]
  (let [state (:state pool)
        id (:id handle)]
    (when-not (live-handle? pool handle)
      (fail! "handle is not held by this pool" {:id id}))
    (swap! state (fn [s]
                   (let [s (if (contains? (:busy s) id)
                             (update s :busy dissoc id)
                             (assoc s :warm (remove-warm (:warm s) id)))
                         s (update s :stamp inc)]
                     (update s :warm (fn [w] (conj (vec w)
                                                   {:stamp (:stamp s)
                                                    :image (:image handle)
                                                    :handle handle}))))))
    (evict-over! pool)
    (count (:warm @state))))

(defn hibernate
  "Suspend handle to a snapshot and return its snapshot-ref (opaque string)."
  [pool handle]
  (let [state (:state pool)
        id (:id handle)]
    (when-not (live-handle? pool handle)
      (fail! "handle is not held by this pool" {:id id}))
    (let [snap-ref (next-id! state "snap-")
          vm (:vm handle)]
      (microvm/save-snapshot! vm snap-ref)
      (try (microvm/stop! vm) (catch Exception _ nil))
      (swap! state (fn [s]
                     (let [s (if (contains? (:busy s) id)
                               (update s :busy dissoc id)
                               (assoc s :warm (remove-warm (:warm s) id)))]
                       (-> s
                           (update :total dec)
                           (assoc-in [:snapshots snap-ref] {:image (:image handle)
                                                            :boot-opts (:boot-opts handle)})))))
      snap-ref)))

(defn restore
  "Resume snapshot-ref into a warm sandbox and return its handle."
  [pool snapshot-ref]
  (let [state (:state pool)
        meta (get-in @state [:snapshots snapshot-ref])]
    (when-not meta
      (fail! "snapshot-ref is not held by this pool" {:snapshot-ref snapshot-ref}))
    (let [{:keys [image boot-opts]} meta
          template {:image image
                    :cache-dir (:cache-dir (:opts pool))
                    :opts {:arch (:arch boot-opts)}}
          _ (microvm/restore-snapshot! template snapshot-ref)
          vm (microvm/start! boot-opts)
          h (->Sandbox (next-id! state "sb-") image vm boot-opts)]
      (swap! state (fn [s]
                     (let [s (-> s (update :total inc) (update :stamp inc))]
                       (update s :warm (fn [w] (conj (vec w)
                                                     {:stamp (:stamp s)
                                                      :image image
                                                      :handle h}))))))
      h)))

(defn stats
  "Return {:warm n, :total n, :snapshots n} for the pool."
  [pool]
  (let [s @(:state pool)]
    {:warm (count (:warm s)) :total (:total s) :snapshots (count (:snapshots s))}))

(defn close!
  "Shut down every sandbox and close the pool."
  [pool]
  (let [state (:state pool)
        s @state]
    (doseq [e (:warm s)]
      (try (microvm/stop! (:vm (:handle e))) (catch Exception _ nil)))
    (doseq [h (vals (:busy s))]
      (try (microvm/stop! (:vm h)) (catch Exception _ nil)))
    (rm-rf! (str (:cache-dir (:opts pool)) "/snapshots"))
    (reset! state {:warm [] :busy {} :total 0 :snapshots {} :id-seq 0 :stamp 0})
    :closed))
