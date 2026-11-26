;; faraday.harness.session — the chat's durable session layer.
;; A session is a thread-id plus the faraday stores the agent loop already uses:
;; the ledger (append-only thread history), the memo (content-addressed), and
;; the two-tier memory (FTS5 + durable checkpoint/resume + post-session
;; extract!). persist! appends every transcript entry to the ledger and
;; checkpoints the transcript under the thread-id; resume reads it back; close!
;; runs the post-session orchestrator so learnings become salient facts.
(ns faraday.harness.session
  (:require [clojure.string :as str]
            [faraday.ledger :as ledger]
            [faraday.memo :as memo]
            [faraday.memory :as memory]))

(defn open
  "Open the session stores for a project root. Returns {:ledger :memo :memory
  :dir}. Idempotent; creates .faraday/session/ on first use."
  [root]
  (let [dir (str root "/.faraday/session")
        l (ledger/new-ledger {:path (str dir "/ledger.db")})
        m (memo/new-memo {:path (str dir "/memo")})
        mem (memory/new-memory {:path (str dir "/memory.db") :ledger l :memo m})]
    {:ledger l :memo m :memory mem :dir dir}))

(defn default-thread-id
  "A fresh thread id per invocation. Run faraday chat without --thread and you
  get a new conversation; pass --thread <id> (printed on exit) to resume one."
  []
  (str "chat-" (java.util.UUID/randomUUID)))

(defn short-thread-id
  "The trailing 8 chars of a thread id — the compact form the footer prints and
  --thread accepts for resume. A full `chat-<uuid>` id is also accepted as-is;
  this only shortens it for display."
  [thread-id]
  (let [t (str (or thread-id ""))]
    (subs t (max 0 (- (count t) 8)))))

(defn resolve-thread
  "Resolve a full or short thread id to the full id. `id` may be the full
  `chat-<uuid>`, or a short suffix (with an optional leading `#`, as the footer
  prints). Returns the full id on a unique match, nil when nothing matches, and
  throws when the short id is ambiguous."
  [stores id]
  (let [id (str/trim (str (or id "")))
        id (if (str/starts-with? id "#") (subs id 1) id)]
    (when (seq id)
      (let [matches (filter #(str/ends-with? % id)
                            (or (seq (memory/list-threads (:memory stores))) []))]
        (cond
          (empty? matches) nil
          (= 1 (count matches)) (first matches)
          :else (throw (ex-info (str "thread id " (pr-str id) " is ambiguous; it matches "
                                     (str/join ", " (sort matches))
                                     ". Use more of the id.")
                                {:faraday.session/ambiguous-id true
                                 :matches (vec (sort matches))})))))))

(defn- entry->msg [thread-id idx e]
  {:v 1
   :thread-id thread-id
   :msg-id (str thread-id "-" idx)
   :kind (case (:role e)
           :user "user-input"
           :tool-result "system"
           :tool-call "yield"
           :assistant "yield"
           "system")
   :payload e})

(defn persist!
  "Append each transcript entry to the ledger (idempotent by msg-id) and
  checkpoint the full transcript under thread-id. Returns the transcript."
  [stores thread-id messages]
  (doseq [[idx e] (map-indexed vector messages)]
    (try (ledger/append! (:ledger stores) (entry->msg thread-id idx e))
         (catch Exception _ nil)))
  (memory/checkpoint! (:memory stores) thread-id messages)
  messages)

(defn resume
  "The checkpointed transcript for thread-id, or nil."
  [stores thread-id]
  (memory/restore (:memory stores) thread-id))

(defn salient
  "The remembered (salient) facts for thread-id: a map fact-key -> value."
  [stores thread-id]
  (memory/salient (:memory stores) thread-id))

(defn history
  "The thread's ledger history (transcript entries in order)."
  [stores thread-id]
  (mapv :payload (ledger/history (:ledger stores) thread-id)))

(defn close!
  "Run the post-session orchestrator over thread-id, then close the memory
  index. Returns the extracted learnings."
  [stores thread-id]
  (try (memory/extract! (:memory stores) thread-id)
       (finally (memory/close! (:memory stores)))))
