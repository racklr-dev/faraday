;; faraday.harness.session — the chat's durable session layer.
;; A session is a thread-id plus the faraday stores the agent loop already uses:
;; the ledger (append-only thread history), the memo (content-addressed), and
;; the two-tier memory (FTS5 + durable checkpoint/resume + post-session
;; extract!). persist! appends every transcript entry to the ledger and
;; checkpoints the transcript under the thread-id; resume reads it back; close!
;; runs the post-session orchestrator so learnings become salient facts.
(ns faraday.harness.session
  (:require [faraday.ledger :as ledger]
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
  "A stable-per-day thread id, so the operator's daily session resumes by
  default until they pass an explicit --thread."
  []
  (str "chat-" (clojure.string/replace (str (java.time.LocalDate/now)) "-" "")))

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
