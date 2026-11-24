;; faraday-thread: thread-keyed sandbox + repo store.
;; Infinity's "ThreadId keys an agent's sandbox and repo state": a Store holds,
;; per thread-id, a live pool sandbox (or its hibernate snapshot-ref) plus a
;; host worktree mounted into it. exec! runs a request's argv inside the
;; thread's sandbox (repo as cwd) and leaves the sandbox warm, so the thread's
;; state persists across its tool calls. yield! suspends the sandbox to a
;; snapshot; resume! restores it. rap-hook is the faraday-rap :run-hook seam.
(ns faraday.thread
  "Thread-keyed sandbox + repo store for a jolt-based control plane.

  A ThreadId keys an agent's sandbox and repo state. The store holds, per
  thread-id, a live sandbox handle (or its hibernate snapshot-ref) plus a host
  worktree mounted into the sandbox. exec! runs a request's argv inside the
  thread's sandbox with the repo as cwd and leaves the sandbox warm, so the
  thread's state persists across its tool calls. yield! suspends the sandbox
  to a snapshot; resume! restores it. rap-hook is the faraday-rap :run-hook
  seam: a rap server opened with :run-hook (thread/rap-hook store) executes
  every invocation against its owning thread's sandbox instead of a per-call
  VM."
  (:require [faraday.pool :as pool]
            [faraday.contracts :as contracts]
            [jolt.microvm :as microvm]
            [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.io File]))

(defrecord Store [opts state lock])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.thread/error true))))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq v))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(def ^:private q (str (char 39)))
(def ^:private dq (str (char 34)))

(defn- sh-quote [s]
  (str q (str/replace s q (str q dq q dq q)) q))

(defn- argv->cmd [argv]
  (str/join " " (map sh-quote argv)))

(defn- rm-rf! [path]
  (when (.exists (File. path))
    (let [r (shell/sh "rm" "-rf" "--" path)]
      (when-not (zero? (:exit r))
        (fail! "rm -rf failed" {:path path :stderr (:err r)}))))
  nil)

(defn- repo-path [root thread-id]
  (str root "/" (str/replace thread-id #"[^A-Za-z0-9._-]" "_")))

(defn- default-repo-fn
  "Resolve a repo-state map to a host worktree path. :direct uses the existing
  :host-path; :git/:jj clone the remote into repo-root/thread-id."
  [repo-map root thread-id]
  (let [mode (:mode repo-map)]
    (case mode
      "direct" (do (non-empty-string! (:host-path repo-map) :host-path)
                   (:host-path repo-map))
      ("git" "jj")
      (let [url (non-empty-string! (:url repo-map) :url)
            dir (repo-path root thread-id)
            clone (if (= mode "jj")
                    ["jj" "git" "clone" url dir]
                    ["git" "clone" url dir])]
        (.mkdirs (File. root))
        (let [r (apply shell/sh clone)]
          (when-not (zero? (:exit r))
            (fail! "repo clone failed" {:mode mode :url url :stderr (:err r)})))
        (when-let [ref (:ref repo-map)]
          (let [co (if (= mode "jj")
                     (shell/sh "jj" "checkout" ref :dir dir)
                     (shell/sh "git" "checkout" ref :dir dir))]
            (when-not (zero? (:exit co))
              (fail! "repo checkout failed" {:mode mode :ref ref :stderr (:err co)}))))
        dir)
      (fail! "unknown repo mode" {:mode mode}))))

(defn new-store
  "Open a thread store over opts: :pool (faraday-pool handle, required),
  :repo-root (host dir for worktrees, required), :repo-fn (fn repo-map root
  thread-id -> host path; default handles :direct/:git/:jj)."
  [opts]
  (let [opts (or opts {})
        p (:pool opts)
        root (non-empty-string! (:repo-root opts) :repo-root)]
    (when-not p (fail! "new-store requires a :pool" {:opts opts}))
    (.mkdirs (File. root))
    (->Store (assoc opts :pool p :repo-root root :repo-fn (or (:repo-fn opts) default-repo-fn))
             (atom {:threads {}})
             (Object.))))

(defn- thread-entry [store thread-id]
  (get-in @(:state store) [:threads thread-id]))

(defn- update-thread! [store thread-id & kvs]
  (swap! (:state store) update-in [:threads thread-id]
         (fnil (fn [e] (apply assoc e kvs)) {})))

(defn- ensure-repo!
  "Materialize the thread's worktree from request's :repo (first use wins).
  Returns the host worktree path, or nil when the thread has no repo."
  [store thread-id request]
  (when-let [repo (:repo request)]
    (let [repo (contracts/decode! :repo-state repo)
          existing (:repo-dir (thread-entry store thread-id))]
      (if existing
        existing
        (let [dir ((:repo-fn (:opts store)) repo (:repo-root (:opts store)) thread-id)]
          (update-thread! store thread-id :repo-dir dir :repo repo)
          dir)))))

(defn- request-with-repo [request repo-dir]
  (if repo-dir
    (update request :mounts (fnil conj []) {:host-path repo-dir :tag "repo" :readonly false})
    request))

(defn sandbox
  "The thread's live sandbox handle: restore its snapshot if hibernated, else
  reuse its warm handle, else acquire a fresh one (with its repo mounted).
  Returns the handle."
  [store thread-id request]
  (locking (:lock store)
    (let [entry (thread-entry store thread-id)
          repo-dir (ensure-repo! store thread-id request)
          request (request-with-repo request repo-dir)]
      (cond
        (:sandbox entry) (:sandbox entry)
        (:snapshot-ref entry)
        (let [h (pool/restore (:pool (:opts store)) (:snapshot-ref entry))]
          (update-thread! store thread-id :sandbox h :snapshot-ref nil)
          h)
        :else
        (let [h (pool/acquire (:pool (:opts store)) request)]
          (update-thread! store thread-id :sandbox h)
          h)))))

(defn exec!
  "Run request's argv inside the thread's sandbox (repo as cwd when the thread
  has one). Returns {:exit :stdout :stderr}. Leaves the sandbox warm."
  [store thread-id request]
  (let [h (sandbox store thread-id request)
        repo-dir (:repo-dir (thread-entry store thread-id))
        cmd (if repo-dir
              (str "cd /mnt/repo && " (argv->cmd (:argv request)))
              (argv->cmd (:argv request)))]
    (microvm/exec (:vm h) cmd :timeout-ms (or (:timeout-ms request) 300000))))

(defn yield!
  "Hibernate the thread's live sandbox to a snapshot-ref and return it. If the
  thread has no live sandbox, returns its existing snapshot-ref or nil."
  [store thread-id]
  (locking (:lock store)
    (let [entry (thread-entry store thread-id)]
      (cond
        (:sandbox entry)
        (let [snap (pool/hibernate (:pool (:opts store)) (:sandbox entry))]
          (update-thread! store thread-id :sandbox nil :snapshot-ref snap)
          snap)
        (:snapshot-ref entry) (:snapshot-ref entry)
        :else nil))))

(defn resume!
  "Restore the thread's snapshot-ref into a live sandbox and return the handle.
  No-op (returns the live handle) when the thread is already live."
  [store thread-id]
  (locking (:lock store)
    (let [entry (thread-entry store thread-id)]
      (cond
        (:sandbox entry) (:sandbox entry)
        (:snapshot-ref entry)
        (let [h (pool/restore (:pool (:opts store)) (:snapshot-ref entry))]
          (update-thread! store thread-id :sandbox h :snapshot-ref nil)
          h)
        :else (fail! "thread has no snapshot to resume" {:thread-id thread-id})))))

(defn close-thread!
  "Release the thread's live sandbox back to the warm pool (if any) and forget
  the thread's sandbox/repo state. Snapshot refs are pool-owned and dropped at
  pool close."
  [store thread-id]
  (locking (:lock store)
    (let [entry (thread-entry store thread-id)]
      (when (:sandbox entry)
        (pool/release (:pool (:opts store)) (:sandbox entry)))
      (swap! (:state store) update :threads dissoc thread-id)))
  nil)

(defn stats
  "Return {:threads n, :live n, :hibernated n} across the store."
  [store]
  (let [ts (vals (get-in @(:state store) [:threads]))]
    {:threads (count ts)
     :live (count (filter :sandbox ts))
     :hibernated (count (filter :snapshot-ref ts))}))

(defn rap-hook
  "The faraday-rap :run-hook seam: (fn request -> {:status :ok|:error :exit
  :stdout :stderr :error}). request must carry :thread-id (rap passes it).
  Executes against the thread's sandbox, leaving it warm."
  [store]
  (fn [request]
    (let [tid (:thread-id request)]
      (when-not tid
        (fail! "thread rap-hook requires :thread-id on the request" {:request request}))
      (try
        (let [r (exec! store tid request)]
          {:status (if (zero? (:exit r)) :ok :error)
           :exit (:exit r)
           :stdout (:stdout r)
           :stderr (:stderr r)})
        (catch Exception e
          {:status :error :exit nil :stdout nil :stderr nil
           :error {:message (or (ex-message e) "thread exec failed")
                   :code (some-> (ex-data e) :type name)}})))))

(defn close!
  "Close the store: release every live sandbox. Does not close the pool."
  [store]
  (locking (:lock store)
    (doseq [entry (vals (get-in @(:state store) [:threads]))]
      (when (:sandbox entry)
        (try (pool/release (:pool (:opts store)) (:sandbox entry)) (catch Exception _ nil))))
    (swap! (:state store) assoc :threads {}))
  nil)
