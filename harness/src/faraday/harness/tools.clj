;; faraday.harness.tools — the chat's non-shell tools.
;; The taskboard/scope/hook surface of the harness DB (faraday.harness) reified
;; as tool-callable functions. Each tool returns the same shape the chat's
;; tool loop already expects from run_shell: {:status :ok|:error :exit int
;; :stdout/:stderr/:error}. Shell stays sandboxed; these run host-side against
;; .faraday/harness.db.
(ns faraday.harness.tools
  (:require [faraday.harness :as h]
            [faraday.pool :as pool]
            [clojure.string :as str]))

(defn find-root
  "Walk up from `start` to the nearest directory with .faraday/harness.db; if
  none, return `start` (a fresh DB can be init'd there)."
  [start]
  (let [start (.getCanonicalPath (java.io.File. start))]
    (loop [dir (.getCanonicalFile (java.io.File. start))]
      (let [db (java.io.File. dir ".faraday" "harness.db")]
        (cond
          (.isFile db) (.getCanonicalPath dir)
          (nil? (.getParentFile dir)) start
          :else (recur (.getParentFile dir)))))))

(defn open
  "Open the harness DB for the project at (find-root root)."
  [root]
  (let [r (find-root root)]
    (h/new-harness {:path (str r "/.faraday/harness.db")})))

(defn- current-project [hd root]
  (or (h/project-by-root hd root)
      (throw (ex-info (str "no project registered for " root
                           "; run `faraday init` there first")
                      {}))))

(defn- ok [s] {:status :ok :exit 0 :stdout (str s) :stderr ""})
(defn- err [s] {:status :error :exit 1 :stdout "" :stderr (str s) :error (str s)})

(defn- task-line [t]
  (str (:id t) "  [" (:priority t) "]  (" (:status t) ") " (:title t)))

(defn- hook-line [hk]
  (str (:id hk) "  [" (:cell hk) "/" (:position hk) "] " (:kind hk)
       "  gate=" (:gate hk)
       (when-let [cmd (get-in hk [:how :command])]
         (str "  " (str/join " " cmd)))))

(def read-only-tools
  #{"board" "task_list" "task_show" "scope_list" "hook_list" "hooks_for" "pool_stats" "read_file"
    "list_tools" "cells" "cell_show" "loop_get"})

(defn dispatch
  "Run a chat tool. `hd` is the harness DB, `root` the project root, `pool-handle`
  the faraday pool handle (nil = pool tools unavailable), `name` the tool, and
  `args` the :args map. Returns the run_shell result plus :progress? (true when
  the tool advanced the goal, false for read-only tools or errors)."
  [hd root pool-handle name args]
  (let [base (try
    (case name
      "pool_stats"
      (if pool-handle
        (ok (pr-str (pool/stats pool-handle)))
        (err "pool is not open"))

      "vm_start"
      (if pool-handle
        (let [n (max 1 (long (or (:n args) 1)))
              image (:image args)
              req (cond-> {:argv ["true"]}
                    image (assoc :image image))]
          (dotimes [_ n]
            (let [h (pool/acquire pool-handle req)]
              (pool/release pool-handle h)))
          (ok (pr-str (pool/stats pool-handle))))
        (err "pool is not open"))

      "board"
      (let [p (current-project hd root)
            tasks (h/task-list hd (:id p))
            lines (mapcat
                   (fn [status]
                     (let [ts (filter #(= status (:status %)) tasks)]
                       (cons (str status " (" (count ts) ")")
                             (map #(str "  " (task-line %)) ts))))
                   ["open" "in_progress" "blocked" "done" "cancelled"])]
        (ok (if (empty? lines) "(board empty)" (str/join "\n" lines))))

      "task_list"
      (let [p (current-project hd root)
            ts (h/task-list hd (:id p) :status (:status args))]
        (ok (if (seq ts)
              (str/join "\n" (map task-line ts))
              (str "(no tasks" (when (:status args) (str " with status " (:status args))) ")"))))

      "task_add"
      (let [p (current-project hd root)
            sid (when-let [rel (:scope args)]
                  (when-let [s (h/scope-by-rel hd (:id p) rel)] (:id s)))
            t (h/task-add! hd {:project-id (:id p)
                               :scope-id sid
                               :title (:title args)
                               :body (:body args)
                               :priority (:priority args)})]
        (ok (str "added " (:id t))))

      "task_show"
      (let [t (h/task hd (:id args))]
        (if t (ok (pr-str t)) (err (str "no task " (:id args)))))

      "task_set_status"
      (let [t (h/task-set-status! hd (:id args) (:status args))]
        (if t (ok (str (:id t) " -> " (:status t)))
            (err (str "no task " (:id args)))))

      "scope_list"
      (let [p (current-project hd root)
            ss (h/scope-list hd (:id p))]
        (ok (if (seq ss)
              (str/join "\n" (map #(str (:rel_path %) (when (= 1 (:is_root %)) "  (root)")) ss))
              "(no scopes)")))

      "hook_list"
      (let [p (current-project hd root)
            hks (h/hook-list hd (:id p))]
        (ok (if (seq hks)
              (str/join "\n" (map hook-line hks))
              "(no hooks)")))

      "hooks_for"
      (let [p (current-project hd root)
            hs (h/resolve-hooks hd (:id p) (or (:rel-path args) ""))]
        (ok (pr-str (select-keys hs [:scope :hooks-vector]))))

      (err (str "unknown tool " (pr-str name))))
    (catch Exception e
      (err (str (or (ex-message e) "tool failed")))))]
    (assoc base :progress? (and (= :ok (:status base))
                                (not (contains? read-only-tools name))))))

(defn- obj [props] {:type "object" :properties props})
(defn- req [props required] (assoc (obj props) :required (vec required)))

(def language-runners
  "run_<lang> code-eval tools: name -> {:prefix [exe arg...] :ext \"ext\"}.
  Inline :code is written to a scratch file under the workspace (.faraday/scratch,
  mounted at the same host path in the sandbox) and run as (prefix path). Adding
  a language is one entry here plus one tool-spec."
  {"run_jolt" {:prefix ["jolt" "run"] :ext "clj"}})

(defn run-code-tool?
  "True when `name` is a run_<lang> code-eval tool."
  [name]
  (contains? language-runners name))

(defn run-code-argv
  "Write inline `code` for a run_* tool to a scratch file under `workspace` and
  return {:argv [runner .. path] :path path}. The scratch dir is the project's
  .faraday/scratch, which the sandbox mounts at the same host path, so the
  absolute path works host-side and in the guest alike."
  [name code workspace]
  (let [{:keys [prefix ext]} (get language-runners name)
        dir (str workspace "/.faraday/scratch")
        path (str dir "/" name "-" (subs (str (java.util.UUID/randomUUID)) 0 8) "." ext)]
    (.mkdirs (java.io.File. dir))
    (spit path (str code))
    {:argv (into (vec prefix) [path]) :path path}))

;; -- structured file tools (read_file / edit_file / write_file) --------------
;; The chat router calls these host-side instead of forcing the model to build
;; sed/grep pipelines through run_shell. Each returns the run_shell result
;; shape plus :progress?.

(defn- file-path
  "Resolve a tool :path against the project root; a path starting with / is
  absolute and passes through."
  [root path]
  (let [p (str path)]
    (if (str/starts-with? p "/") p (str root "/" p))))

(defn- count-occurrences
  "Literal count of needle in hay (no regex), for edit_file uniqueness."
  [hay needle]
  (let [hay (str hay) needle (str needle)]
    (if (empty? needle)
      0
      (loop [i 0 n 0]
        (let [j (.indexOf hay needle i)]
          (if (neg? j) n (recur (+ j (count needle)) (inc n))))))))

(defn read-file
  "read_file: return the file at :path, or its 1-based inclusive [:start :end]
  line range (whole file when omitted). Read-only, so :progress? is false."
  [root args]
  (try
    (let [path (file-path root (:path args))
          f (java.io.File. path)]
      (if-not (.isFile f)
        (assoc (err (str "read_file: " path " does not exist")) :progress? false)
        (let [lines (vec (str/split-lines (slurp f)))
              n (count lines)
              start (if (nil? (:start args)) 1 (max 1 (long (:start args))))
              end (if (nil? (:end args)) n (min n (long (:end args))))]
          (if (> start end)
            (assoc (err (str "read_file: start " start " > end " end)) :progress? false)
            (assoc (ok (str/join "\n" (subvec lines (dec start) end))) :progress? false)))))
    (catch Exception e
      (assoc (err (str "read_file failed: " (or (ex-message e) "?"))) :progress? false))))

(defn edit-file
  "edit_file: replace the one exact occurrence of :old in the file at :path
  with :new. Errors when :old is absent or matches more than once."
  [root args]
  (try
    (let [path (file-path root (:path args))
          f (java.io.File. path)
          old (str (:old args))
          new (str (:new args))]
      (if-not (.isFile f)
        (assoc (err (str "edit_file: " path " does not exist")) :progress? false)
        (let [content (slurp f)
              n (count-occurrences content old)]
          (cond
            (zero? n) (assoc (err (str "edit_file: old text not found in " path)) :progress? false)
            (> n 1) (assoc (err (str "edit_file: old text matches " n " times — make it more specific")) :progress? false)
            :else (do (spit f (.replace content old new))
                      (assoc (ok (str "replaced 1 occurrence in " path)) :progress? true))))))
    (catch Exception e
      (assoc (err (str "edit_file failed: " (or (ex-message e) "?"))) :progress? false))))

(defn write-file
  "write_file: write :content to the file at :path, creating parent directories."
  [root args]
  (try
    (let [path (file-path root (:path args))
          f (java.io.File. path)
          content (str (:content args))]
      (when-let [parent (.getParentFile f)] (.mkdirs parent))
      (spit f content)
      (assoc (ok (str "wrote " (.length f) " bytes to " path)) :progress? true))
    (catch Exception e
      (assoc (err (str "write_file failed: " (or (ex-message e) "?"))) :progress? false))))

(def tool-specs
  "The tools: name, one-line description, the prompt args shape, and the JSON
  schema for OpenAI-style native function calling."
  [{:name "run_shell"
    :desc "run a shell command in the sandbox (cwd = this project dir)"
    :args "{:argv [\"cmd\" \"arg\" ...]}"
    :parameters (req {:argv {:type "array" :items {:type "string"}}} ["argv"])}
   {:name "run_jolt"
    :desc "evaluate jolt (Clojure) code in the sandbox; returns what it prints"
    :args "{:code \"(println (range 5))\"}"
    :parameters (req {:code {:type "string"}} ["code"])}
   {:name "read_file"
    :desc "read a file, or a line range, from the project — use for targeted reads instead of sed -n"
    :args "{:path rel/or/abs :start 10 :end 30}"
    :parameters (req {:path {:type "string"} :start {:type "integer"} :end {:type "integer"}} ["path"])}
   {:name "edit_file"
    :desc "replace one exact string in a file with another — errors unless :old matches exactly once (prefer this over sed -i)"
    :args "{:path ... :old exact-existing-text :new replacement}"
    :parameters (req {:path {:type "string"} :old {:type "string"} :new {:type "string"}} ["path" "old" "new"])}
   {:name "write_file"
    :desc "write the whole file at :path with :content (creates parent dirs, overwrites)"
    :args "{:path ... :content ...}"
    :parameters (req {:path {:type "string"} :content {:type "string"}} ["path" "content"])}
   {:name "list_tools"
    :desc "list every available tool with its args shape"
    :args "{}"
    :parameters (obj {})}
   {:name "cells"
    :desc "list the standing Context cells (note lines) that ride every request"
    :args "{}"
    :parameters (obj {})}
   {:name "cell_add"
    :desc "add a standing Context cell: a one-line :note + the full :body text (survives restarts)"
    :args "{:note one-line-summary :body full-text}"
    :parameters (req {:note {:type "string"} :body {:type "string"}} ["note"])}
   {:name "cell_show"
    :desc "show one Context cell's full body by seq"
    :args "{:id 3}"
    :parameters (req {:id {:type "integer"}} ["id"])}
   {:name "cell_drop"
    :desc "drop Context cells by seq (keep the rest)"
    :args "{:ids [2 3]}"
    :parameters (req {:ids {:type "array" :items {:type "integer"}}} ["ids"])}
   {:name "cell_keep"
    :desc "keep only these Context cells by seq (drop the rest)"
    :args "{:ids [1 2]}"
    :parameters (req {:ids {:type "array" :items {:type "integer"}}} ["ids"])}
   {:name "loop_get"
    :desc "show the loop manifest (the editable working rules + turn budget)"
    :args "{}"
    :parameters (obj {})}
   {:name "loop_set"
    :desc "rewrite the editable loop surface: :rules [strings] and/or :turn-budget int"
    :args "{:rules [\"...\"] :turn-budget 96}"
    :parameters (obj {:rules {:type "array" :items {:type "string"}} :turn-budget {:type "integer"}})}
   {:name "pool_stats"
    :desc "warm VM pool stats"
    :args "{}"
    :parameters (obj {})}
   {:name "vm_start"
    :desc "start (warm) N VMs in the pool"
    :args "{:n 3}"
    :parameters (obj {:n {:type "integer"} :image {:type "string"}})}
   {:name "board"
    :desc "kanban board grouped by status"
    :args "{}"
    :parameters (obj {})}
   {:name "task_list"
    :desc "list tasks, optionally filtered by status"
    :args "{:status \"open\"}"
    :parameters (obj {:status {:type "string"}})}
   {:name "task_add"
    :desc "add a task to the board"
    :args "{:title \"...\" :scope \"rel/path\" :body \"...\" :priority \"normal|high|low\"}"
    :parameters (req {:title {:type "string"}
                      :scope {:type "string"}
                      :body {:type "string"}
                      :priority {:type "string"}} ["title"])}
   {:name "task_show"
    :desc "show one task by id"
    :args "{:id \"faraday-...\"}"
    :parameters (req {:id {:type "string"}} ["id"])}
   {:name "task_set_status"
    :desc "move a task (open|in_progress|blocked|done|cancelled)"
    :args "{:id \"...\" :status \"in_progress\"}"
    :parameters (req {:id {:type "string"} :status {:type "string"}} ["id" "status"])}
   {:name "scope_list"
    :desc "list the project's folder scopes"
    :args "{}"
    :parameters (obj {})}
   {:name "hook_list"
    :desc "list guardrail hooks"
    :args "{}"
    :parameters (obj {})}
   {:name "hooks_for"
    :desc "resolve the hooks a scope path injects"
    :args "{:rel-path \"src/db\"}"
    :parameters (req {:rel-path {:type "string"}} ["rel-path"])}])

(defn openai-tools
  "The OpenAI tools array (one {type function, function {name description
  parameters}} per tool) for native function calling."
  []
  (mapv (fn [{:keys [name desc parameters]}]
          {:type "function"
           :function {:name name :description desc :parameters parameters}})
        tool-specs))

(declare tool-prompt)

(defn list-tools
  "list_tools: return the tool catalog as a run_shell-shaped result, so the model
  can always enumerate its tools deterministically instead of recalling the prompt."
  []
  {:status :ok :exit 0 :stdout (tool-prompt) :stderr ""})

(defn tool-prompt
  "The tool list rendered into the agentic system prompt."
  []
  (str/join "\n"
            (map (fn [{:keys [name desc args]}]
                   (str "- " name " — " desc "\n    args: " args))
                 tool-specs)))

(defn language-prompt
  "Guidance for the run_* code-eval tools, injected into the agentic system
  prompt so the model knows what to run, when, and where."
  []
  (str "Code evaluation:\n"
       "- run_jolt evaluates jolt (Clojure) code in the sandbox at the project root,\n"
       "  with deps from the project's deps.edn, and returns what the code prints.\n"
       "  Use it to run a form and read the value, verify a guess about the codebase,\n"
       "  or probe a namespace. Prefer run_jolt for evaluating Clojure; use run_shell\n"
       "  for edits, git, and everything else shell-shaped."))
