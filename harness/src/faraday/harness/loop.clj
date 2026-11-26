;; faraday.harness.loop — the loop as data (samizdat): the agent's working
;; rules and turn budget live in .faraday/loop.edn and are editable through the
;; loop tools. The immutable core (reply shape, tool names, guardrails) stays
;; hardcoded in chat/agentic-system; only the editable surface is data.
(ns faraday.harness.loop
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def default-loop
  "The built-in loop manifest. :rules are the working rules injected into the
  system prompt; :turn-budget caps tool turns per user message."
  {:rules
   ["Treat the user message as the task. Terse or ambiguous? Make the smallest reasonable assumption and start; ask only when the choice changes the work."
    "Plan then edit: name the files and changes in one line, then execute — don't keep reading once you have enough."
    "Read before you change; prefer grep -n / sed -n over whole-file dumps; never repeat a failed command unchanged."
    "In run_shell the project directory is mounted at its own host path, so the paths in Context work verbatim. Board/scope/hook tools only work when Context says the taskboard is ready."
    "When the task is to show or read a file, answer with the file's full text verbatim — never a summary, never 'see the tool output above'. Paste it raw, no fence."]
   :turn-budget 96})

(defn loop-file
  [root]
  (str root "/.faraday/loop.edn"))

(defn read-loop
  "The merged loop manifest: default-loop with any keys present in the file
  overriding. A corrupt file degrades to the default."
  [root]
  (try
    (let [f (io/file (loop-file root))]
      (if (.isFile f)
        (let [v (edn/read-string (slurp f :encoding "UTF-8"))]
          (if (map? v) (merge default-loop v) default-loop))
        default-loop))
    (catch Exception _ default-loop)))

(defn write-loop!
  "Atomically write the loop manifest and return it."
  [root m]
  (let [f (io/file (loop-file root))]
    (.mkdirs (.getParentFile f))
    (let [tmp (io/file (str (.getPath f) ".tmp." (System/nanoTime)))]
      (spit tmp (pr-str m) :encoding "UTF-8")
      (.renameTo tmp f)
      m)))

(defn loop-lines
  "The editable rules rendered as system-prompt lines."
  [loop-map]
  (mapv #(str "- " %) (:rules loop-map)))

;; -- tool result shapes ------------------------------------------------------
(defn- ok [s] {:status :ok :exit 0 :stdout (str s) :stderr ""})
(defn- err [s] {:status :error :exit 1 :stdout "" :stderr (str s) :error (str s)})

(defn get-tool
  "loop_get: return the current loop manifest as EDN."
  [root _]
  (ok (pr-str (read-loop root))))

(defn set-tool
  "loop_set: rewrite the editable loop surface (:rules vector, :turn-budget int).
  The immutable core (reply shape, tool names, guardrails) is not data and cannot
  be changed here."
  [root args]
  (try
    (let [m (read-loop root)
          rules (:rules args)
          budget (:turn-budget args)
          m (cond-> m
              (vector? rules) (assoc :rules (mapv str rules))
              (and (number? budget) (pos? budget) (<= budget 500)) (assoc :turn-budget (long budget)))]
      (write-loop! root m)
      (ok (pr-str m)))
    (catch Exception e
      (err (str "loop_set failed: " (or (ex-message e) "?"))))))
