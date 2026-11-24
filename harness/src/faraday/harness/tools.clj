;; faraday.harness.tools — the chat's non-shell tools.
;; The taskboard/scope/hook surface of the harness DB (faraday.harness) reified
;; as tool-callable functions. Each tool returns the same shape the chat's
;; tool loop already expects from run_shell: {:status :ok|:error :exit int
;; :stdout/:stderr/:error}. Shell stays sandboxed; these run host-side against
;; .faraday/harness.db.
(ns faraday.harness.tools
  (:require [faraday.harness :as h]
            [clojure.string :as str]))

(defn open
  "Open the harness DB for the project at root."
  [root]
  (h/new-harness {:path (str root "/.faraday/harness.db")}))

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

(defn dispatch
  "Run a chat tool. `name` is a tool name; `args` is the :args map from the
  model's {:tool-call ..}. Returns the run_shell-shaped result map."
  [hd root name args]
  (try
    (case name
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
      (err (str (or (ex-message e) "tool failed"))))))

(defn- obj [props] {:type "object" :properties props})
(defn- req [props required] (assoc (obj props) :required (vec required)))

(def tool-specs
  "The tools: name, one-line description, the prompt args shape, and the JSON
  schema for OpenAI-style native function calling."
  [{:name "run_shell"
    :desc "run a shell command in the sandbox (cwd = this project dir)"
    :args "{:argv [\"cmd\" \"arg\" ...]}"
    :parameters (req {:argv {:type "array" :items {:type "string"}}} ["argv"])}
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

(defn tool-prompt
  "The tool list rendered into the agentic system prompt."
  []
  (str/join "\n"
            (map (fn [{:keys [name desc args]}]
                   (str "- " name " — " desc "\n    args: " args))
                 tool-specs)))
