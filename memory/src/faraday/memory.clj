;; faraday-memory: two-tier memory + FTS5 over ledger/memo + checkpoint/resume.
;; Salient facts stay inline (the working set an agent carries in context); the
;; rest demote to a breadcrumb index — compact pointers back to the full record.
;; A SQLite FTS5 index makes the ledger history and the memo values searchable.
;; Checkpoints are durable per thread-id, and extract! runs the post-session
;; orchestrator: it pulls learnings out of a thread's history into salient
;; facts.
(ns faraday.memory
  "Two-tier agent memory for a jolt-based control plane.

  Salient facts stay inline (the working set an agent carries in context); the
  rest demote to a breadcrumb index — compact pointers back to the full record.
  A SQLite FTS5 index makes the ledger history and the memo values searchable.
  Checkpoints are durable per thread-id, and extract! runs the post-session
  orchestrator: it pulls learnings out of a thread's history into salient
  facts."
  (:require [db.sqlite :as sql]
            [clojure.edn :as edn]
            [faraday.ledger :as ledger]
            [faraday.memo :as memo]))

(defrecord Memory [conn ledger memo path closed])

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.memory/error true))))

(defn- now [] (str (java.time.Instant/now)))

(defn- k->str [k] (name k))
(defn- str->k [s] (keyword s))
(defn- value->text [v] (pr-str v))
(defn- text->value [s] (edn/read-string s))

(defn- ensure-parent! [path]
  (when-let [p (.getParentFile (java.io.File. path))]
    (.mkdirs p))
  path)

(defn new-memory
  "Open (or create) a memory over opts: :path (index db), :ledger (faraday-ledger
  handle), :memo (faraday-memo handle). Creates the facts/checkpoints/FTS tables
  on first open."
  [opts]
  (let [path (:path opts)
        ledg (:ledger opts)
        mem (:memo opts)]
    (when-not (string? path) (fail! "new-memory requires a string :path" {:opts opts}))
    (when-not ledg (fail! "new-memory requires a :ledger" {:opts opts}))
    (when-not mem (fail! "new-memory requires a :memo" {:opts opts}))
    (ensure-parent! path)
    (let [conn (sql/open path)]
      (try
        (sql/query conn
                   (str "create table if not exists facts ("
                        "thread_id text not null,"
                        "fact_key text not null,"
                        "value text not null,"
                        "tier text not null,"
                        "ts text not null,"
                        "primary key (thread_id, fact_key))") [])
        (sql/query conn
                   (str "create table if not exists checkpoints ("
                        "thread_id text primary key,"
                        "state text not null,"
                        "ts text not null)") [])
        (sql/query conn
                   (str "create virtual table if not exists mem_fts using fts5("
                        "thread_id, source, doc_id, body)") [])
        (->Memory conn ledg mem path (atom false))
        (catch Throwable t
          (try (sql/close conn) (catch Throwable _))
          (throw t))))))

;; -- two-tier facts ----------------------------------------------------------

(defn note!
  "Store a salient (inline) fact under fact-key for thread-id. fact-key is a
  keyword (or string). Upserts in place. Returns value."
  [mem thread-id fact-key value]
  (let [conn (:conn mem)
        k (k->str fact-key)
        ts (now)]
    (sql/query conn
               (str "insert into facts (thread_id, fact_key, value, tier, ts) "
                    "values (?, ?, ?, 'salient', ?) "
                    "on conflict(thread_id, fact_key) do update set "
                    "value = excluded.value, tier = excluded.tier, ts = excluded.ts")
               [thread-id k (value->text value) ts])
    value))

(defn demote!
  "Move a salient fact to the breadcrumb tier (the out-of-line index). Returns
  true when the fact was present and salient, false otherwise."
  [mem thread-id fact-key]
  (let [conn (:conn mem)
        k (k->str fact-key)
        before (first (sql/query conn
                                 "select tier from facts where thread_id = ? and fact_key = ?"
                                 [thread-id k]))]
    (when before
      (sql/query conn
                 "update facts set tier = 'breadcrumb', ts = ? where thread_id = ? and fact_key = ?"
                 [(now) thread-id k]))
    (boolean (= "salient" (:tier before)))))

(defn promote!
  "Move a breadcrumb fact back to the salient tier. Returns true when the fact
  was present and a breadcrumb, false otherwise."
  [mem thread-id fact-key]
  (let [conn (:conn mem)
        k (k->str fact-key)
        before (first (sql/query conn
                                 "select tier from facts where thread_id = ? and fact_key = ?"
                                 [thread-id k]))]
    (when before
      (sql/query conn
                 "update facts set tier = 'salient', ts = ? where thread_id = ? and fact_key = ?"
                 [(now) thread-id k]))
    (boolean (= "breadcrumb" (:tier before)))))

(defn- tier-map [mem thread-id tier]
  (reduce (fn [m row] (assoc m (str->k (:fact_key row)) (text->value (:value row))))
          {}
          (sql/query (:conn mem)
                     "select fact_key, value from facts where thread_id = ? and tier = ? order by fact_key"
                     [thread-id tier])))

(defn salient
  "The inline working set for thread-id: a map fact-key -> value."
  [mem thread-id]
  (tier-map mem thread-id "salient"))

(defn breadcrumbs
  "The out-of-line breadcrumb index for thread-id: a map fact-key -> value."
  [mem thread-id]
  (tier-map mem thread-id "breadcrumb"))

(defn facts
  "All facts for thread-id: a map fact-key -> {:value v :tier t}."
  [mem thread-id]
  (reduce (fn [m row]
            (assoc m (str->k (:fact_key row))
                   {:value (text->value (:value row)) :tier (:tier row)}))
          {}
          (sql/query (:conn mem)
                     "select fact_key, value, tier from facts where thread_id = ? order by fact_key"
                     [thread-id])))

;; -- FTS5 search over ledger + memo ------------------------------------------

(defn index-thread!
  "Index thread-id's ledger history and the memo values its messages point at
  (payload :memo-key) into the FTS5 index. Replaces the thread's prior rows.
  Returns the number of indexed documents."
  [mem thread-id]
  (let [conn (:conn mem)
        hist (ledger/history (:ledger mem) thread-id)]
    (sql/query conn "delete from mem_fts where thread_id = ?" [thread-id])
    (doseq [msg hist]
      (sql/query conn
                 "insert into mem_fts (thread_id, source, doc_id, body) values (?, 'ledger', ?, ?)"
                 [thread-id (or (:msg-id msg) (str "seq-" (:seq msg))) (pr-str msg)])
      (when-let [mk (get-in msg [:payload :memo-key])]
        (when (memo/contains? (:memo mem) mk)
          (let [v (memo/get (:memo mem) mk)]
            (sql/query conn
                       "insert into mem_fts (thread_id, source, doc_id, body) values (?, 'memo', ?, ?)"
                       [thread-id mk (pr-str v)])))))
    (count (sql/query conn "select doc_id from mem_fts where thread_id = ?" [thread-id]))))

(defn search
  "FTS5 search across the indexed ledger+memo documents. query is an FTS5 match
  expression. Returns a vector of {:thread-id :source :doc-id :body} maps."
  [mem query]
  (mapv (fn [row]
          {:thread-id (:thread_id row)
           :source (:source row)
           :doc-id (:doc_id row)
           :body (:body row)})
        (sql/query (:conn mem)
                   "select thread_id, source, doc_id, body from mem_fts where mem_fts match ? order by rank"
                   [query])))

;; -- durable checkpoint / resume ----------------------------------------------

(defn checkpoint!
  "Durably checkpoint live state under thread-id (the identity key). Returns
  state."
  [mem thread-id state]
  (let [conn (:conn mem)
        ts (now)]
    (sql/query conn
               (str "insert into checkpoints (thread_id, state, ts) values (?, ?, ?) "
                    "on conflict(thread_id) do update set state = excluded.state, ts = excluded.ts")
               [thread-id (value->text state) ts])
    state))

(defn restore
  "The checkpointed live state for thread-id, or nil if none."
  [mem thread-id]
  (let [row (first (sql/query (:conn mem)
                              "select state from checkpoints where thread_id = ?"
                              [thread-id]))]
    (when row (text->value (:state row)))))

(defn list-threads
  "All thread-ids with a durable checkpoint, sorted ascending. This is the
  identity key for resume: a thread only becomes resumable once checkpointed."
  [mem]
  (mapv :thread_id
        (sql/query (:conn mem)
                   "select thread_id from checkpoints order by thread_id" [])))

(defn drop-checkpoint!
  "Remove thread-id's checkpoint. Returns true if it was present."
  [mem thread-id]
  (let [conn (:conn mem)
        had (boolean (seq (sql/query conn
                                     "select 1 from checkpoints where thread_id = ?"
                                     [thread-id])))]
    (sql/query conn "delete from checkpoints where thread_id = ?" [thread-id])
    had))

;; -- post-session orchestrator ------------------------------------------------

(defn default-extract
  "The default post-session orchestrator: derive learnings from a thread's
  history deterministically. Returns a map fact-key -> fact."
  [history]
  (let [reports (filter #(= "report" (:kind %)) history)
        last-report (last reports)
        errors (->> history
                    (keep (fn [m] (or (get-in m [:payload :error]) (:error m))))
                    vec)
        kind-counts (frequencies (map :kind history))]
    (cond-> {:message-count (count history)}
      last-report (assoc :last-report
                         (select-keys last-report [:msg-id :seq :kind :payload]))
      (seq errors) (assoc :errors errors)
      (seq kind-counts) (assoc :kind-counts kind-counts))))

(defn extract!
  "Run the post-session orchestrator over thread-id: read its ledger history,
  derive learnings with the :extract fn (default default-extract), and store
  each learning as a salient fact. Returns the map of stored learnings."
  [mem thread-id & {:as opts}]
  (let [extract (or (:extract opts) default-extract)
        hist (ledger/history (:ledger mem) thread-id)
        learnings (extract hist)]
    (doseq [[k v] learnings]
      (note! mem thread-id k v))
    learnings))

(defn close!
  "Close the memory's index db (does not close the ledger/memo it was given).
  Idempotent."
  [mem]
  (when (compare-and-set! (:closed mem) false true)
    (sql/close (:conn mem)))
  nil)
