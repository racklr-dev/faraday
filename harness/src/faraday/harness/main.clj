;; faraday.harness.main — the runnable faraday CLI harness.
;; `jolt -M:harness -- <command> [args]` (or bin/faraday <command> [args]).
;; Commands: init, project, scope, hook, task, hooks-for, run, board, help.
;; `run` is the end-to-end honesty path: task start -> resolve scope hooks ->
;; compile the middleware spine (faraday.agent.mw) -> launch the loop over the
;; scheduler -> fire per-cell hooks -> record every hook_runs row -> extract
;; memory -> finalize with expected⊆ran and blocking⊆pass.
;;
;; Cell granularity: hooks fire per-cell through faraday.agent.mw/wrap-guardrails
;; (orient/step/invoke/finalize/judge). Non-command hooks (middleware, predicate,
;; contract) are recorded honestly as :skip, so they never block a turn.
(ns faraday.harness.main
  (:require [faraday.harness :as h]
            [faraday.harness.run :as run]
            [faraday.harness.supervisor :as sup]
            [faraday.harness.model-proposer :as mp]
            [faraday.harness.chat :as chat]
            [faraday.harness.config :as config]
            [faraday.harness.llm :as llm]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; -- argv --------------------------------------------------------------------

(def ^:private bool-flags #{"disable" "help" "force" "verbose"})

(defn parse-args
  "--key v / --key=v -> :key v; boolean flags (no value) -> :key true; bare
  positionals accumulate."
  [args]
  (loop [as (seq args) pos [] flags {}]
    (if-not as
      {:positional pos :flags flags}
      (let [a (first as)]
        (cond
          (str/starts-with? a "--")
          (let [[k v] (str/split (subs a 2) #"=" 2)
                key (keyword k)]
            (cond
              v (recur (next as) pos (assoc flags key v))
              (contains? bool-flags k) (recur (next as) pos (assoc flags key true))
              (nil? (second as)) (recur (next as) pos (assoc flags key true))
              :else (recur (nnext as) pos (assoc flags key (second as)))))
          :else (recur (next as) (conj pos a) flags))))))

(defn flag [flags k] (get flags k))
(defn- edn-flag [flags k] (when-let [v (get flags k)] (edn/read-string v)))
(defn- parse-long* [s] (when s (Long/parseLong (str s))))

(defn- invocation-cwd
  "The directory the operator invoked faraday from: FARADAY_CWD (set by
  bin/faraday), else the process cwd. Project discovery walks up from here,
  not from the tool's repo root."
  []
  (or (not-empty (System/getenv "FARADAY_CWD"))
      (System/getProperty "user.dir")))

;; -- project root + db -------------------------------------------------------

(defn- find-project-root
  "Walk up from the invocation cwd to the first directory containing
  .faraday/harness.db."
  []
  (loop [dir (.getCanonicalFile (io/file (invocation-cwd)))]
    (let [db (io/file dir ".faraday" "harness.db")]
      (cond
        (.exists db) (.getCanonicalPath dir)
        (nil? (.getParentFile dir))
        (throw (ex-info "no .faraday/harness.db found (run `faraday init` first)" {}))
        :else (recur (.getParentFile dir))))))

(defn- open-db
  ([] (h/new-harness {:path (str (find-project-root) "/.faraday/harness.db")}))
  ([root] (h/new-harness {:path (str root "/.faraday/harness.db")})))

(defn- current-project [hd root]
  (or (h/project-by-root hd root)
      (throw (ex-info (str "no project registered for " root "; run `faraday init`") {}))))

(defn- usage []
  (println (str "faraday — the harness CLI\n"
                "\n"
                "usage: jolt -M:harness -- <command> [args]   (or bin/faraday <command> [args])\n"
                "\n"
                "  init   --root <dir> [--name N] [--default-manifest M]\n"
                "         create .faraday/harness.db and register the project\n"
                "  project [add]        show (or add) the project for cwd\n"
                "  scope  add --rel-path src/db [--meta EDN] [--root]\n"
                "  scope  list\n"
                "  scope  set-meta --rel-path src/db --meta EDN\n"
                "  hook   add --scope src --cell step --position post --kind slop-cop\n"
                "              --how EDN --why S [--gate blocking] [--priority N]\n"
                "  hook   list | enable ID | disable ID | remove ID\n"
                "  task   add --scope src --title S [--body S] [--priority high]\n"
                "              [--epic ID] [--manifest EDN]\n"
                "  task   list [--status open] | show ID | start ID | close ID\n"
                "  hooks-for <rel-path>   reify the scope's nested hooks as loop-as-data\n"
                "  run    <task-id> [--manifest file.edn] [--exec shell|sandbox]\n"
                "         fire the task's hooks around a yieldable scheduler loop, record\n"
                "         hook_runs, and finalize the honesty invariant; --exec sandbox runs\n"
                "         tools in a warm faraday-pool microVM (needs /dev/kvm), shell runs\n"
                "         them on the host (default)\n"
                "  improve <task-id> [--n N] [--delta D] [--proposer budget|model]\n"
                "         observe the task's loop run, propose a manifest rewrite, and gate it\n"
                "         (validate + soak + rollback); persists the winner to the task.\n"
                "         --proposer model uses faraday.harness.model-proposer; the key is\n"
                "         resolved from FARADAY_LLM_API_KEY/DEEPSEEK_API_KEY env, then\n"
                "         ~/.faraday/settings.json, then ~/.nodecode/auth.json\n"
                "  board                  kanban by status\n"
                "  chat    [--model M] [--base-url U] [--system S] [--wrap N]\n"
                "          [--thread ID] [--exec sandbox|shell]\n"
                "          [--mount host:guest[:ro|rw]]... a chat TUI\n"
                "          over the DeepSeek completions API (glimmer-tui); tools run\n"
                "          in a jolt-sandbox microVM by default, with ~/.faraday and\n"
                "          ~/.nodecode mounted at the sandbox user's home when present\n"
                "  config [provider P | role R | agent A | model-step] [--check]\n"
                "          print (or validate) the resolved settings.json surface:\n"
                "          providers, roles, agents, and the escalation ladder\n")))

;; -- commands ----------------------------------------------------------------

(defn- cmd-init [args]
  (let [{:keys [flags]} (parse-args args)
        root (.getCanonicalPath (io/file (or (flag flags :root) (invocation-cwd))))
        name (or (flag flags :name) (.getName (io/file root)))
        dm (or (flag flags :default-manifest) "default")
        hd (h/new-harness {:path (str root "/.faraday/harness.db")})
        p (h/project-add! hd {:root-path root :name name :default-manifest dm})]
    (h/scope-add! hd {:project-id (:id p) :rel-path "" :is-root true :meta {}})
    (prn p)))

(defn- cmd-project [args]
  (let [sub (first args)]
    (if (= sub "add")
      (cmd-init (rest args))
      (let [root (find-project-root)
            hd (open-db root)]
        (prn (current-project hd root))))))

(defn- cmd-scope [args]
  (let [{:keys [positional flags]} (parse-args args)
        sub (first positional)
        root (find-project-root)
        hd (open-db root)
        p (current-project hd root)]
    (case sub
      "add" (prn (h/scope-add! hd {:project-id (:id p)
                                   :rel-path (or (flag flags :rel-path) (second positional))
                                   :meta (edn-flag flags :meta)
                                   :is-root (boolean (flag flags :root))}))
      "list" (doseq [s (h/scope-list hd (:id p))] (prn s))
      "set-meta" (let [scope-id (:id (h/scope-by-rel hd (:id p)
                                                      (or (flag flags :rel-path) (second positional))))]
                   (prn (h/scope-set-meta! hd scope-id (edn-flag flags :meta))))
      (usage))))

(defn- cmd-hook [args]
  (let [{:keys [positional flags]} (parse-args args)
        sub (first positional)
        root (find-project-root)
        hd (open-db root)
        p (current-project hd root)]
    (case sub
      "add" (let [scope-id (or (flag flags :scope-id)
                               (:id (h/scope-by-rel hd (:id p) (or (flag flags :scope) (flag flags :rel-path)))))]
              (prn (h/hook-add! hd {:project-id (:id p)
                                    :scope-id scope-id
                                    :cell (or (flag flags :cell) "*")
                                    :position (or (flag flags :position) "pre")
                                    :kind (flag flags :kind)
                                    :how (edn-flag flags :how)
                                    :why (flag flags :why)
                                    :gate (or (flag flags :gate) "advisory")
                                    :priority (parse-long* (flag flags :priority))
                                    :timeout-ms (parse-long* (flag flags :timeout-ms))})))
      "list" (doseq [hk (h/hook-list hd (:id p))] (prn hk))
      "enable" (prn (h/hook-set-enabled! hd (second positional) true))
      "disable" (prn (h/hook-set-enabled! hd (second positional) false))
      "remove" (prn (h/hook-remove! hd (second positional)))
      (usage))))

(defn- cmd-task [args]
  (let [{:keys [positional flags]} (parse-args args)
        sub (first positional)
        rest (rest positional)]
    (case sub
      "add" (let [root (find-project-root)
                  hd (open-db root)
                  p (current-project hd root)]
              (prn (h/task-add! hd {:project-id (:id p)
                                    :scope-id (when-let [s (flag flags :scope)]
                                                (:id (h/scope-by-rel hd (:id p) s)))
                                    :title (or (flag flags :title) (first rest))
                                    :body (or (flag flags :body) "")
                                    :priority (or (flag flags :priority) "normal")
                                    :epic-id (flag flags :epic)
                                    :manifest (edn-flag flags :manifest)
                                    :assignee (flag flags :assignee)
                                    :dedup-key (flag flags :dedup-key)})))
      "list" (let [root (find-project-root)
                   hd (open-db root)
                   p (current-project hd root)]
               (doseq [t (h/task-list hd (:id p) :status (flag flags :status))] (prn t)))
      "show" (prn (h/task (open-db) (first rest)))
      "start" (prn (h/task-start (open-db) (first rest)))
      "close" (prn (h/task-set-status! (open-db) (first rest) "done"))
      "set-status" (prn (h/task-set-status! (open-db) (first rest)
                                             (or (flag flags :status) (second rest))))
      (usage))))

(defn- cmd-hooks-for [args]
  (let [{:keys [positional flags]} (parse-args args)
        root (find-project-root)
        hd (open-db root)
        p (current-project hd root)]
    (prn (h/resolve-hooks hd (:id p) (or (first positional) (flag flags :scope) "")))))

(defn- cmd-board [args]
  (let [root (find-project-root)
        hd (open-db root)
        p (current-project hd root)
        tasks (h/task-list hd (:id p))]
    (doseq [status ["open" "in_progress" "blocked" "done" "cancelled"]]
      (let [ts (filter #(= status (:status %)) tasks)]
        (println (str status " (" (count ts) ")"))
        (doseq [t ts]
          (println (str "  " (:id t) "  [" (:priority t) "] " (:title t)
                        (when (:scope_id t)
                          (str "  @ " (some-> (h/scope hd (:scope_id t)) :rel_path))))))))))

;; -- run: the honest end-to-end path -----------------------------------------

(defn run-task!
  "Delegate to faraday.harness.run/run-task! — the shared honesty path."
  [root task-id & {:keys [manifest exec]}]
  (run/run-task! root task-id :manifest manifest :exec exec))

(defn- cmd-run [args]
  (let [{:keys [positional flags]} (parse-args args)
        task-id (first positional)
        root (find-project-root)
        manifest (when-let [f (flag flags :manifest)] (edn/read-string (slurp f)))]
    (if-not task-id
      (usage)
      (prn (run-task! root task-id :manifest manifest :exec (flag flags :exec))))))

;; -- improve: the self-improvement gate --------------------------------------

(defn- cmd-improve [args]
  (let [{:keys [positional flags]} (parse-args args)
        task-id (first positional)
        root (find-project-root)]
    (if-not task-id
      (usage)
      (let [proposer-kind (or (flag flags :proposer) "budget")
            proposer (case proposer-kind
                       "budget" (sup/budget-relaxer :delta (parse-long* (flag flags :delta)))
                       "model" (mp/model-proposer {:model (flag flags :model)
                                                       :base-url (flag flags :base-url)})
                       (throw (ex-info "unknown proposer (want budget|model)"
                                       {:proposer proposer-kind})))]
        (prn (sup/improve! {:root root
                            :task-id task-id
                            :n (parse-long* (flag flags :n))
                            :proposer proposer}))))))

;; -- config: the resolved settings.json surface ---------------------------------

(defn- redact-keys
  "Replace :api-key values with :<set> or :<unset> so a config dump never
  prints a secret; recurse into maps and map values."
  [m]
  (reduce-kv
   (fn [acc k v]
     (assoc acc k
            (cond
              (= :api-key k) (if (seq (str v)) "<set>" "<unset>")
              (map? v) (redact-keys v)
              :else v)))
   {} m))

(defn- cmd-config [args]
  (let [{:keys [positional flags]} (parse-args args)
        sub (first positional)
        root (try (find-project-root) (catch Exception _ nil))
        opts (cond-> {} root (assoc :project-root root))
        cfg (config/load-config opts)]
    (cond
      (flag flags :check)
      (let [problems (config/validate-config cfg)]
        (if (empty? problems)
          (println "config: OK")
          (doseq [p problems] (println "config: problem:" p))))

      (= sub "provider")
      (prn (redact-keys (config/resolve-provider cfg (or (flag flags :provider)
                                                         (second positional)) opts)))

      (= sub "role")
      (prn (redact-keys (config/resolve-role cfg (or (flag flags :role)
                                                     (second positional)) opts)))

      (= sub "agent")
      (prn (redact-keys (config/resolve-agent cfg (or (flag flags :agent)
                                                      (second positional)) opts)))

      (= sub "model-step")
      (let [ms (config/model-step-opts cfg opts #(llm/provider %))]
        (prn (redact-keys
              {:providers (keys (:providers ms))
               :role-config (into {} (map (fn [[k v]] [k (if (map? v) (:provider v) v)])
                                          (:role-config ms)))
               :escalation-ladder (:escalation-ladder ms)
               :max-repairs (:max-repairs ms)
               :max-escalations (:max-escalations ms)
               :breaker-threshold (:breaker-threshold ms)})))

      :else
      (do
        (println "faraday config — resolved settings.json surface")
        (println "providers: " (pr-str (sort (keys (:providers cfg)))))
        (println "roles:     " (pr-str (into {} (map (fn [[k v]]
                                                        [k (if (map? v) (:provider v) v)])
                                                      (:roles cfg)))))
        (println "agents:    " (pr-str (sort (keys (:agents cfg)))))
        (println "escalation:" (pr-str (config/resolve-escalation cfg)))
        (doseq [p (config/validate-config cfg)]
          (println "config: problem:" p))))))

;; -- dispatch ----------------------------------------------------------------

(defn -main [& args]
  (try
    (let [args (vec args)]
      (if (empty? args)
        (usage)
        (case (first args)
          "init" (cmd-init (rest args))
          "project" (cmd-project (rest args))
          "scope" (cmd-scope (rest args))
          "hook" (cmd-hook (rest args))
          "task" (cmd-task (rest args))
          "hooks-for" (cmd-hooks-for (rest args))
          "run" (cmd-run (rest args))
          "improve" (cmd-improve (rest args))
          "board" (cmd-board (rest args))
          "chat" (chat/cmd-chat (rest args))
          "config" (cmd-config (rest args))
          "help" (usage)
          (do (println "unknown command:" (first args))
              (usage)))))
    (catch Exception e
      (binding [*out* *err*]
        (println "faraday: error:" (or (ex-message e) (str e))))
      (System/exit 1))))
