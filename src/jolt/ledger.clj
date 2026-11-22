;; jolt-ledger: durable idempotent thread state + processed-ID dedup.
;; Storage is sqlite via jolt-lang/db (db.sqlite), one connection per ledger.
;; The ledger is the single writer for Thread Messages (jolt-contracts SPEC.md
;; §1): it assigns each message a per-thread monotonic :seq, dedups on :msg-id,
;; and answers history/processed queries. The durable body of each message is
;; the canonical wire JSON (string keys), read back as keyword-keyed maps.
(ns jolt.ledger
  "Durable, idempotent thread state for the jolt control plane.

  The ledger is the single writer for Thread Messages (jolt-contracts
  SPEC.md §1): it assigns each message a per-thread monotonic seq,
  dedups on msg-id, and answers history/processed queries. Storage is
  delegated to jolt-lang/db (SQLite through db.sqlite)."
  (:require [db.sqlite :as sql]
            [clojure.data.json :as json]))

(def ^:private kinds
  "The Thread Message kinds SPEC.md §1 accepts."
  #{"spawn" "inherit" "report" "yield" "user-input" "system"})

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :jolt.ledger/error true))))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq v))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(defn- validate! [msg]
  (when-not (map? msg)
    (fail! "message must be a map" {:value msg}))
  (let [thread-id (:thread-id msg)
        msg-id (:msg-id msg)
        kind (:kind msg)
        v (:v msg)]
    (non-empty-string! thread-id :thread-id)
    (non-empty-string! msg-id :msg-id)
    (when (and (some? v) (not= v 1))
      (fail! "unknown message version" {:v v}))
    (when (and (some? kind) (not (contains? kinds kind)))
      (fail! "unknown message kind" {:kind kind}))
    (when (= (:parent-id msg) thread-id)
      (fail! "parent-id must not equal thread-id" {:thread-id thread-id}))))

(defn- normalize
  "The canonical message map to persist: the contract keys in SPEC.md §1 order
  (with :seq replaced by the assigned value), then any extra forward-compatible
  keys sorted by name so the stored JSON is deterministic."
  [msg seq']
  (let [core (array-map :v (or (:v msg) 1)
                        :thread-id (:thread-id msg)
                        :parent-id (get msg :parent-id)
                        :msg-id (:msg-id msg)
                        :seq seq'
                        :kind (:kind msg)
                        :ts (or (:ts msg) (str (java.time.Instant/now)))
                        :payload (or (:payload msg) {}))
        extras (->> msg
                    (remove (fn [[k _]] (contains? core k)))
                    (sort-by (fn [[k _]] (str k))))]
    (reduce (fn [m [k v]] (assoc m k v)) core extras)))

(defn- keywordize
  "Deep-convert JSON string keys to keywords (round-tripping the in-process
  representation). JSON has no sets or lists, but the walk covers them for
  symmetry."
  [x]
  (cond
    (map? x)    (reduce-kv (fn [m k v] (assoc m (keyword k) (keywordize v))) {} x)
    (vector? x) (mapv keywordize x)
    (seq? x)    (map keywordize x)
    (set? x)    (into #{} (map keywordize) x)
    :else       x))

(defn- with-tx
  "Run f with an open BEGIN IMMEDIATE transaction; COMMIT on success, ROLLBACK
  on any throw. IMMEDIATE takes the write lock up front so the dedup check and
  the seq assignment are one atomic unit."
  [conn f]
  (sql/query conn "BEGIN IMMEDIATE" [])
  (try
    (let [r (f)]
      (sql/query conn "COMMIT" [])
      r)
    (catch Throwable t
      (try (sql/query conn "ROLLBACK" []) (catch Throwable _))
      (throw t))))

(defn- ensure-parent-dir! [path]
  (let [f (java.io.File. path)]
    (when-let [p (.getParentFile f)]
      (.mkdirs p)))
  path)

(defn new-ledger
  "Open (or create) a ledger rooted at opts, a map with at least :path.
  Returns an opaque ledger handle. Creates the parent directory if needed,
  then the messages/threads tables on first open."
  [opts]
  (let [path (or (:path opts)
                 (fail! "new-ledger requires a :path" {:opts opts}))
        _ (ensure-parent-dir! path)
        conn (sql/open path)]
    (try
      (sql/query conn
                 (str "create table if not exists messages ("
                      "msg_id text primary key,"
                      "thread_id text not null,"
                      "seq integer not null,"
                      "body text not null)")
                 [])
      (sql/query conn
                 "create index if not exists messages_thread_seq on messages(thread_id, seq)"
                 [])
      (sql/query conn
                 "create table if not exists threads (thread_id text primary key, last_seq integer not null)"
                 [])
      {:conn conn :path path :closed (atom false)}
      (catch Throwable t
        (try (sql/close conn) (catch Throwable _))
        (throw t)))))

(defn append!
  "Append one Thread Message map (SPEC.md §1) to the ledger.
  Assigns the next :seq for its :thread-id; if a message with the same
  :msg-id was already appended, returns the original result and does not
  duplicate it. Returns {:seq n, :deduped? bool}."
  [ledger msg]
  (validate! msg)
  (let [conn (:conn ledger)
        thread-id (:thread-id msg)
        msg-id (:msg-id msg)]
    (with-tx conn
      (fn []
        (if-let [existing (first (sql/query conn
                                            "select seq from messages where msg_id = ?"
                                            [msg-id]))]
        {:seq (:seq existing) :deduped? true}
        (let [cur (first (sql/query conn
                                    "select last_seq from threads where thread_id = ?"
                                    [thread-id]))
              next-seq (if cur (inc (long (:last_seq cur))) 0)
              body (json/write-str (normalize msg next-seq))]
          (sql/query conn
                     "insert into messages (msg_id, thread_id, seq, body) values (?, ?, ?, ?)"
                     [msg-id thread-id next-seq body])
          (if cur
            (sql/query conn "update threads set last_seq = ? where thread_id = ?"
                       [next-seq thread-id])
            (sql/query conn "insert into threads (thread_id, last_seq) values (?, ?)"
                       [thread-id next-seq]))
          {:seq next-seq :deduped? false}))))))

(defn history
  "Return the messages of thread-id in :seq order, as a vector of maps
  (keyword-keyed, the assigned :seq included)."
  [ledger thread-id]
  (mapv (fn [row] (keywordize (json/read-str (:body row))))
        (sql/query (:conn ledger)
                   "select body from messages where thread_id = ? order by seq"
                   [thread-id])))

(defn processed?
  "True iff a message with msg-id has already been appended (idempotency probe)."
  [ledger msg-id]
  (boolean (seq (sql/query (:conn ledger)
                           "select 1 from messages where msg_id = ? limit 1"
                           [msg-id]))))

(defn last-seq
  "The highest :seq seen for thread-id, or nil if none."
  [ledger thread-id]
  (let [row (first (sql/query (:conn ledger)
                              "select last_seq from threads where thread_id = ?"
                              [thread-id]))]
    (when row (:last_seq row))))

(defn close!
  "Flush and close the ledger. Idempotent: a second close is a no-op."
  [ledger]
  (when (compare-and-set! (:closed ledger) false true)
    (sql/close (:conn ledger)))
  nil)
