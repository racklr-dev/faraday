;; faraday.harness.chat — an agentic chat TUI over the DeepSeek (OpenAI-compatible)
;; completions API, drawn with glimmer-tui.
;;
;; Unlike the original streaming-only chat, this drives the model through a tool
;; loop: when the model replies with {:tool-call {:name .. :args {:argv [..]}}}
;; the tool runs for real (host shell by default; the :exec seam swaps in a
;; sandbox), the result goes back into the conversation, and the loop continues
;; until the model answers {:done "..."} (or plain text). This is the same
;; tool-call/EDN action contract faraday.agent and faraday.rap already speak.
;;
;; Pure, tested pieces: word-wrap, build-messages, format-tool-result, parse-action,
;; parse-task-line/task-row/board-rows/task-detail-rows, undo/redo branch rewind.
(ns faraday.harness.chat
  (:require [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [faraday.harness :as h]
            [faraday.harness.run :as run]
            [faraday.harness.commands :as cmd]
            [faraday.harness.config :as config]
            [faraday.harness.context :as ctx]
            [faraday.harness.dialog :as dialog]
            [faraday.harness.guard :as guard]
            [faraday.harness.jev :as jev]
            [faraday.harness.llm :as llm]
            [faraday.harness.session :as session]
            [faraday.harness.settings :as settings]
            [faraday.harness.tools :as tools]
            [faraday.themes :as themes]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core :as tui]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.keys :as k]
            [glimmer-tui.widget :as w]
            [glimmer-tui.widgets.scroll :as scroll]
            [jolt.host]
            [jolt.markdown.core :as md]))

;; register the dialog's zero-size :focus-sink widget once, at load
(dialog/install!)

;; -- colours -----------------------------------------------------------------
;; All colours resolve through faraday.themes/color at paint time; this file
;; carries no hex literals and no theme-file knowledge. The active pack is
;; selected by ~/.faraday/settings.json {"theme": "...", "appearance": "..."}.

;; -- pure: word wrapping ------------------------------------------------------
;; Labels do not wrap (they are measured at their widest line and truncated to
;; their rect), so a chat message is pre-wrapped here into lines that fit, then
;; rendered as one multi-line label (a newline makes a label taller).

(defn word-wrap
  "Wrap `s` into a vector of lines no wider than `width` chars, preserving
  existing newlines and hard-breaking words longer than `width`."
  [s width]
  (let [w (max 1 (long width))]
    (vec
     (mapcat
      (fn [line]
        (if (<= (count line) w)
          [line]
          (loop [rem (str/triml line) acc []]
            (let [n (count rem)]
              (if (<= n w)
                (conj acc rem)
                (let [chunk (subs rem 0 w)
                      cut (str/last-index-of chunk " ")]
                  (if (and cut (pos? cut))
                    (recur (subs rem (inc cut)) (conj acc (subs rem 0 cut)))
                    (recur (subs rem w) (conj acc chunk)))))))))
      (str/split s #"\n" -1)))))

;; -- the tool loop, pure ------------------------------------------------------

(defn hard-wrap
  "Hard-break `s` into lines of at most `width` chars, preserving leading
  whitespace and never reflowing at spaces. Code wraps with this rather than
  word-wrap, because a code line's indentation is part of its meaning."
  [s width]
  (let [w (max 1 (long width))]
    (loop [rem s acc []]
      (if (<= (count rem) w)
        (conj acc rem)
        (recur (subs rem w) (conj acc (subs rem 0 w)))))))

(def agentic-system
  (str "You are faraday, the terminal work agent for this project. The operator types a work order; you carry it out end-to-end with tools, then answer with what you did and what you verified.\n"
       "\n"
       "Tools (each reply is exactly one EDN map, nothing else):\n"
       (tools/tool-prompt)
       "\n"
       (tools/language-prompt)
       "\n"
       "Working rules:\n"
       "- Treat the user message as the task. Terse or ambiguous? Make the smallest reasonable assumption and start; ask only when the choice changes the work.\n"
       "- Plan then edit: name the files and changes in one line, then execute — don't keep reading once you have enough.\n"
       "- Read before you change; prefer grep -n / sed -n over whole-file dumps; never repeat a failed command unchanged.\n"
       "- In run_shell the project directory is mounted at its own host path, so the paths in Context work verbatim. Board/scope/hook tools only work when Context says the taskboard is ready.\n"
       "- When the task is to show or read a file, answer with the file's full text verbatim — never a summary, never 'see the tool output above'. Paste it raw, no fence.\n"
       "\n"
       "Answer with {:done \"...\"} (or plain text for a short answer). Report concretely: files changed or commands run, and what you verified. When the task was to show/read a file, the answer is the file's contents in full. No preamble, no filler."))

;; -- working context ---------------------------------------------------------
;; Injected into the system prompt so the model spends its turn budget on the
;; task instead of rediscovering where it is. setup-chat probes the facts; the
;; builder stays pure so tests can pin it.

(defn boot-context-lines
  "Compact standing facts about faraday's surroundings, as system-prompt lines.
  Pure; setup-chat probes the facts so tests can pin the rendering."
  [{:keys [cwd proj-root board-ready? project-desc git-summary recent-commits tasks]}]
  (cond-> []
    cwd (conj (if (and proj-root (= proj-root cwd))
                (str "Working directory (project root): " cwd)
                (str "Working directory: " cwd)))
    (and proj-root (not= proj-root cwd)) (conj (str "Project root: " proj-root))
    project-desc  (conj (str "Project: " project-desc))
    (some? board-ready?) (conj (if board-ready?
                                 "Taskboard: ready — board/scope/hook/pool tools work."
                                 "Taskboard: NOT initialized — board/scope/hook tools will error; use run_shell instead (or run `faraday init` first)."))
    git-summary  (conj (str "Git: " git-summary))
    recent-commits (conj (str "Recent commits: " recent-commits))
    (seq tasks)  (conj (str "Open tasks:\n" (str/join "\n" (map #(str "  " %) tasks))))))

(defn top-level-summary
  "First `limit` sorted names in `dir`, joined, for the working context."
  ([dir] (top-level-summary dir 12))
  ([dir limit]
   (try
     (let [f (java.io.File. dir)
           names (sort (seq (.list f)))]
       (when names
         (str (str/join " " (take limit names))
              (when (> (count names) limit) " …"))))
     (catch Exception _ nil))))

(defn readme-desc
  "A one-line project descriptor: the first substantive non-heading line of the
  repo's README, truncated; nil when there is none. Falls back in setup-chat to
  top-level-summary when no README yields a line."
  [dir]
  (try
    (when-let [f (some (fn [n] (let [f (java.io.File. dir n)]
                                 (when (.isFile f) f)))
                       ["README.md" "README" "README.rst" "README.org" "readme.md" "Readme.md"])]
      (let [line (->> (str/split-lines (slurp f))
                      (map str/trim)
                      (remove str/blank?)
                      (remove #(or (str/starts-with? % "#")
                                   (str/starts-with? % "<!--")
                                   (str/starts-with? % "==")
                                   (str/starts-with? % "--")))
                      first)]
        (when line (subs line 0 (min 160 (count line))))))
    (catch Exception _ nil)))

(defn git-branch
  "The current branch of the git repo at `dir`, or nil when not a repo."
  [dir]
  (try
    (let [r (h/run-command ["git" "-C" dir "rev-parse" "--abbrev-ref" "HEAD"] 4000)]
      (when (and (zero? (:exit r)) (seq (str/trim (:out r))))
        (str/trim (:out r))))
    (catch Exception _ nil)))

(defn git-status
  "Short working-tree summary: \"clean\" or \"dirty (N changed[, M staged][, K untracked])\"; nil when not a repo."
  [dir]
  (try
    (let [r (h/run-command ["git" "-C" dir "status" "--porcelain"] 4000)]
      (when (zero? (:exit r))
        (let [lines (remove str/blank? (str/split-lines (:out r)))]
          (if (empty? lines)
            "clean"
            (let [staged (count (filter #(contains? #{\M \A \D \R \C} (first %)) lines))
                  untracked (count (filter #(str/starts-with? % "??") lines))
                  changed (- (count lines) untracked)]
              (str "dirty (" changed " changed"
                   (when (pos? staged) (str ", " staged " staged"))
                   (when (pos? untracked) (str ", " untracked " untracked"))
                   ")"))))))
    (catch Exception _ nil)))

(defn recent-commits
  "Last `n` commit subjects as one line \"abc123 subject | def456 ...\"; nil when not a repo."
  ([dir] (recent-commits dir 4))
  ([dir n]
   (try
     (let [r (h/run-command ["git" "-C" dir "log" (str "-" n) "--format=%h %s"] 4000)]
       (when (zero? (:exit r))
         (let [lines (remove str/blank? (str/split-lines (:out r)))]
           (when (seq lines) (str/join " | " lines)))))
     (catch Exception _ nil))))

(defn git-summary
  "One line \"<branch> · <status>\" (or just the status when detached); nil when not a repo."
  [dir]
  (when-let [status (git-status dir)]
    (let [branch (git-branch dir)]
      (if (and branch (not= branch "HEAD"))
        (str branch " · " status)
        status))))

(defn format-tool-result
  "One line describing a finished tool run, then its output. Used both for the
  model's next turn and for the transcript."
  [e]
  (str "tool " (:name e) " finished with status " (name (:status e))
       (when (some? (:exit e)) (str ", exit " (:exit e)))
       (when (seq (:error e)) (str ", error: " (:error e)))
       ":\n" (or (:text e) "")))

(defn transcript->markdown
  "The conversation as Markdown: each entry becomes a role heading and its
  text. Tool calls are fenced with the argv the model emitted; tool results
  reuse format-tool-result. Pure; tests pin the format."
  [messages]
  (str "# faraday session\n\n"
       (str/join
        "\n\n"
        (map
         (fn [e]
           (case (:role e)
             :user (str "## user\n\n" (or (:text e) ""))
             :assistant (str "## assistant\n\n" (or (:text e) ""))
             :tool-call (let [argv (seq (:argv (:args e)))]
                          (str "## tool-call\n\n```\n"
                               "$" (when (or (:name e) argv) " ")
                               (or (:name e) "")
                               (when argv (str " " (str/join " " (map str argv))))
                               "\n```"))
             :tool-result (str "## tool-result\n\n"
                               (format-tool-result
                                (select-keys e [:name :status :exit :error :text])))
             (str "## " (name (or (:role e) :system)) "\n\n"
                  (or (:text e) (:content e) ""))))
         messages))))

(defn export-session!
  "Write `messages` as Markdown. `path` may be a file path, a directory, or
  blank: blank or a directory writes faraday-<thread-id>.md there (blank -> the
  :cwd opt, default the process cwd). Returns the absolute path written.
  opts: :cwd (default dir), :spit-fn (test seam, default clojure.core/spit)."
  [messages thread-id path & [{:keys [cwd spit-fn]
                               :or {cwd (System/getProperty "user.dir")
                                    spit-fn spit}}]]
  (let [p (cond
            (nil? path) ""
            (str/starts-with? path "~/") (str (System/getProperty "user.home") (subs path 1))
            :else path)
        f (java.io.File. p)
        dir (cond
              (str/blank? p) cwd
              (.isDirectory f) p
              :else nil)
        target (java.io.File. (if dir
                                (str dir java.io.File/separator "faraday-" (or thread-id "session") ".md")
                                p))
        af (.getAbsoluteFile target)
        md (transcript->markdown messages)]
    (spit-fn (.getPath af) md)
    (.getPath af)))

;; -- taskboard: pure shell pieces ----------------------------------------------
;; The /board and /task shell commands render the harness taskboard. These are
;; the pure parts: parsing the /task line and turning task maps into dialog
;; rows. The impure DB calls live in chat-app, which is the single place ids
;; become behaviour (the same split commands.clj vs chat-app keeps).

(def task-board-statuses
  "The canonical board columns, in order, for /board and the boot context."
  ["open" "in_progress" "blocked" "done" "cancelled"])

(defn parse-task-line
  "Parse the /task rest text into {:sub .. :positional [..] :flags {..}}.
  --k v and --k=v are collected as keyword flags (the task flag set: priority,
  scope, body, status, epic, assignee, dedup-key); bare tokens accumulate, the
  first being the subcommand. A blank line -> :sub nil (the shell defaults it
  to list). Pure, so tests pin the flag/positional split."
  [line]
  (let [line (str/trim (str line))]
    (if (str/blank? line)
      {:sub nil :positional [] :flags {}}
      (loop [tokens (str/split line #"\s+")
             pos []
             flags {}]
        (if (empty? tokens)
          {:sub (first pos) :positional (vec (rest pos)) :flags flags}
          (let [t (first tokens)
                more (next tokens)]
            (cond
              (and (str/starts-with? t "--") (str/includes? t "="))
              (let [[k v] (str/split (subs t 2) #"=" 2)]
                (recur more pos (assoc flags (keyword k) v)))

              (str/starts-with? t "--")
              (let [k (keyword (subs t 2))]
                (if (seq more)
                  (recur (next more) pos (assoc flags k (first more)))
                  (recur more pos (assoc flags k true))))

              :else
              (recur more (conj pos t) flags))))))))

(defn task-row
  "One task as a dialog row: the title labels the row; the value carries the
  id, priority and status. Pure over the task map."
  [t]
  {:label (str (:title t))
   :value (str (:id t) "  [" (or (:priority t) "normal") "]  " (or (:status t) ""))
   :task-id (:id t)})

(defn board-rows
  "Dialog rows for /board: every task in canonical column order, so the sheet
  reads as a kanban grouped by status. Pure over the task list."
  [tasks]
  (let [order (zipmap task-board-statuses (range))]
    (->> tasks
         (sort-by (fn [t] [(get order (:status t) 99) (or (:priority t) "normal") (:title t)]))
         (mapv task-row))))

(defn task-detail-rows
  "Dialog rows for /task show: one row per key field, body/manifest clipped.
  Pure over the task map."
  [t]
  (let [clip (fn [s n] (let [s (str s)] (if (> (count s) n) (str (subs s 0 n) "…") s)))]
    (vec
     (concat
      [{:label "id" :value (str (:id t))}
       {:label "title" :value (str (:title t))}
       {:label "status" :value (str (:status t))}
       {:label "priority" :value (str (:priority t))}]
      (when (:scope_id t) [{:label "scope" :value (str (:scope_id t))}])
      (when (:epic_id t) [{:label "epic" :value (str (:epic_id t))}])
      (when (:assignee t) [{:label "assignee" :value (str (:assignee t))}])
      (when (seq (str (:body t))) [{:label "body" :value (clip (:body t) 64)}])
      (when (:manifest t) [{:label "manifest" :value (clip (pr-str (:manifest t)) 64)}])))))

(defn- fold-summary
  "A deterministic one-line summary of the folded entries: counts, errors, and
  the last task, so the model keeps enough context without the raw transcript."
  [es]
  (let [roles (frequencies (map :role es))
        errors (seq (keep :error es))
        last-user (last (filter #(= :user (:role %)) es))]
    (str "(earlier context: " (count es) " entries, "
         (or (:tool-call roles) 0) " tool calls, "
         (or (:tool-result roles) 0) " tool results"
         (when errors (str ", " (count errors) " errors"))
         (when last-user
           (str "; last task: " (subs (str (:text last-user)) 0 (min 80 (count (str (:text last-user)))))))
         ")")))

(defn build-messages
  "Turn conversation entries into OpenAI chat messages. Entries use the chat's
  own roles (:user :assistant :tool-call :tool-result); a tool-call becomes an
  assistant turn carrying the EDN the model emitted, and a tool-result becomes
  a user turn describing what the tool did. opts: :context-budget (default 40) caps
  the transcript entries sent; older ones fold into one summary line so a long
  conversation stays inside the model's window and the prefix stays cache-friendly."
  [system entries & {:keys [context-budget] :or {context-budget 40}}]
  (let [n (count entries)
        entries (if (> n context-budget)
                  (let [dropped (- n context-budget)]
                    (into [{:role :user :text (fold-summary (take dropped entries))}]
                          (drop dropped entries)))
                  entries)]
    (into [{:role "system" :content system}]
          (mapcat
           (fn [e]
             (case (:role e)
               :user [{:role "user" :content (:text e)}]
               :assistant [{:role "assistant" :content (:text e)}]
               :tool-call [{:role "assistant"
                            :content (pr-str {:tool-call {:name (:name e) :args (:args e)}})}]
               :tool-result [{:role "user" :content (format-tool-result e)}]
               [{:role "user" :content (str (:text e))}]))
           entries))))

(defn parse-action
  "The EDN action a model content string carries, or nil when it is plain text."
  [content]
  (try
    (let [a (llm/content->action content)]
      (when (map? a) a))
    (catch Exception _ nil)))

;; -- pure: undo/redo (branch rewind) ------------------------------------------
;; /undo moves the last user turn — the final :user entry and everything after
;; it (the assistant answer plus any tool calls/results) — onto a :redo stack;
;; /redo returns to that branch by popping it back. A new message clears the
;; :redo stack (the abandoned branch is gone), the same contract an editor's
;; undo/redo keeps.

(defn last-user-index
  "Index of the last :user entry in `messages`, or nil when there is no user turn."
  [messages]
  (last (keep-indexed (fn [i e] (when (= :user (:role e)) i)) messages)))

(defn undo-turn
  "Split `messages` into [before rewound]: rewound is the last user turn, the
  final :user entry and everything after it. Returns nil when there is no user
  turn to rewind."
  [messages]
  (let [v (vec messages)]
    (when-let [i (last-user-index v)]
      [(subvec v 0 i) (subvec v i)])))

(defn history-undo
  "Rewind the last turn of a history map {:messages [...] :redo [branch ...]}:
  move the last user turn (and everything after it) onto the top of :redo.
  Returns the new history map, or nil when there is nothing to rewind."
  [{:keys [messages] :as history}]
  (when-let [[before rewound] (undo-turn messages)]
    (assoc history
           :messages before
           :redo (conj (vec (:redo history)) rewound))))

(defn history-redo
  "Return to the branch the last rewind left: pop the top of :redo back onto
  :messages. Returns the new history map, or nil when nothing was rewound."
  [{:keys [messages redo] :as history}]
  (when (seq redo)
    (assoc history
           :messages (into (vec messages) (peek redo))
           :redo (pop redo))))

(def ^:dynamic *interrupt*
  "Bound by chat-session around run-turn! on the worker thread; tool executors
  poll it to stop a running command mid-flight." nil)

(def ^:private dump-guard-re
  #"(?:(?:^|[;&|]\s*)|(?:bash|sh)\s+(?:-lc|-c)\s+)(?:cat\s+\S|head\s+-n\s*[0-9]{4,}\s+\S)")

(defn- dump-guard
  "A guidance message when argv would dump a whole file, else nil. Lives at the
  exec seam so a `cat file` never floods the model context: the model is told to
  re-read surgically instead of being allowed the dump."
  [argv]
  (let [cmd (str/join " " (map str argv))]
    (when (re-find dump-guard-re cmd)
      (str "Blocked: this would dump a whole file and flood the context. "
           "Reads are capped at ~80 lines. Use `grep -n PATTERN file` to find the line, "
           "then `sed -n A,Bp file` (keep B-A <= 80) to read just that part."))))

(defn host-exec
  "The default tool executor: run argv on the host in faraday's cwd, or evaluate
  a run_<lang> tool's :code via a scratch file. Returns
  {:status :ok|:error :exit int|nil :stdout :stderr :error}. The :exec seam lets
  a sandbox (jolt-sandbox microVM with volume mounts) take this slot instead."
  [name args]
  (let [argv (if (tools/run-code-tool? name)
               (:argv (tools/run-code-argv name (:code args) (System/getProperty "user.dir")))
               (:argv args))]
    (cond
      (not (and (vector? argv) (seq argv)))
      {:status :error :error "tool args must be {:argv [..]}, or {:code \"...\"} for a run_* tool"}

      (not (or (contains? #{"run_shell" "shell" "exec"} name)
               (tools/run-code-tool? name)))
      {:status :error :error (str "unknown tool " (pr-str name))}

      :else
      (if-let [guard (dump-guard argv)]
        {:status :error :error guard}
        (let [r (if *interrupt*
                (h/run-command-interruptible argv 120000 *interrupt*)
                (h/run-command argv 120000))]
        (cond
          (:interrupted? r)
          {:status :error :error "interrupted" :interrupted? true :stdout (:out r)}

          (or (:timeout? r) (nil? (:exit r)))
          {:status :error :error "timed out" :stdout (:out r)}

          :else
          {:status (if (zero? (:exit r)) :ok :error)
           :exit (:exit r)
           :stdout (:out r)
           :stderr ""}))))))

(defn- argv->cmd
  "Single-quote each argv element for the guest sh."
  [argv]
  (str/join " " (map (fn [s] (str "'" (str/replace (str s) "'" "'\\''") "'")) argv)))

(defn group-members
  "Members of `group` parsed from /etc/group-style lines. Returns a set of names,
  or nil when the group line is absent."
  [lines group]
  (let [line (some (fn [l]
                     (let [l (str/trim l)]
                       (when (str/starts-with? l (str group ":")) l)))
                   lines)]
    (when line
      (set (remove str/blank?
                   (str/split (or (last (str/split line #":" -1)) "") #","))))))

(defn kvm-preflight
  "Describe the host's /dev/kvm for the sandbox: {:exists? :writable? :member?}.
  :member? says whether the current user is listed in the kvm group, so the
  advice differs (re-login vs usermod)."
  []
  (let [kvm (java.io.File. "/dev/kvm")
        user (System/getProperty "user.name")
        members (try
                  (group-members (str/split-lines (slurp "/etc/group")) "kvm")
                  (catch Exception _ nil))]
    {:exists? (.exists kvm)
     :writable? (and (.exists kvm) (.canWrite kvm))
     :member? (boolean (and members (members user)))}))

(defn sandbox-preflight!
  "HARD gate: sandbox selected but this process can't open /dev/kvm -> exit 1
  with a copy-pasteable fix. `faraday chat --exec shell` bypasses this."
  []
  (let [{:keys [exists? writable? member?]} (kvm-preflight)]
    (when (not writable?)
      (binding [*out* *err*]
        (println "⚠ faraday: /dev/kvm" (if exists? "not writable" "missing")
                 "— the sandbox will fail closed.")
        (if member?
          (do (println "   you're in the kvm group, but this shell predates it.")
              (println "   fix:  sg kvm -c \"faraday chat ...\"    (or log out and back in)"))
          (do (println "   you're not in the kvm group.")
              (println "   fix:  sudo usermod -aG kvm $USER    (then log out and back in)")))
        (System/exit 1)))))

(defn sandbox-exec-factory
  "Build a stateful tool executor over jolt.sandbox: a warm alpine microVM with
  the operator's cwd volume-mounted at its own absolute path (workspace-guest =
  the host cwd, so host paths work inside). The pool boots lazily on the
  first tool call and is reused; (close) stops it. Returns {:exec (fn [name
  args] -> result) :close (fn [])}. Tool calls FAIL CLOSED: if the sandbox cannot
  boot, the call returns {:status :error :error ..} and every later call returns
  the same error — there is no host-exec fallback. opts: :workspace (host dir at
  /workspace), :mounts (extra host-dir->guest-dir virtiofs mounts, same shapes
  jolt.microvm/start! takes: {host guest} map, [{:host :guest :tag}] specs, or
  [[host guest] ..] pairs), :project-dir (dir whose deps.edn provides the
  sandbox stack), :cache-dir, :image (default alpine:3.20), :nix-flake (flake
  devShell ref layered into the VM, e.g. \".#default\")."
  [opts]
  (let [state (atom {:pool nil :failed? false :boot-error nil})
        sb-make-pool (requiring-resolve 'jolt.sandbox/make-pool)
        sb-pool-exec (requiring-resolve 'jolt.sandbox/pool-exec)
        sb-close-pool! (requiring-resolve 'jolt.sandbox/close-pool!)
        workspace (or (:workspace opts) (System/getProperty "user.dir"))
        mounts (:mounts opts)
        project-dir (or (:project-dir opts)
                      (System/getenv "FARADAY_HARNESS_DIR")
                      (System/getProperty "user.dir"))
        cache-dir (or (:cache-dir opts)
                      (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
        image (or (:image opts) "alpine:3.20")
        packages (:packages opts)
        nix-flake (:nix-flake opts)
        egress (:egress opts)
        ensure-pool! (fn []
                       (or (:pool @state)
                           (let [p (sb-make-pool {:image image
                                                  :cache-dir cache-dir
                                                  :project-dir project-dir
                                                  :workspace workspace
                                                  :workspace-guest workspace
                                                  :mounts mounts
                                                  :writable-home? true
                                                  :packages packages
                                                  :nix-flake nix-flake
                                                  :egress egress})]
                             (swap! state assoc :pool p)
                             p)))
        recycle-pool! (fn []
                        (when-let [p (:pool @state)]
                          (try (sb-close-pool! p) (catch Exception _ nil))
                          (swap! state dissoc :pool)))]
    {:exec (fn [name args]
             (cond
               (not (or (contains? #{"run_shell" "shell" "exec"} name)
                        (tools/run-code-tool? name)))
               {:status :error :error (str "unknown tool " (pr-str name))}

               (:failed? @state)
               {:status :error :error (:boot-error @state) :fatal? true}

               :else
               (let [argv (if (tools/run-code-tool? name)
                            (:argv (tools/run-code-argv name (:code args) workspace))
                            (:argv args))]
                 (if (not (and (vector? argv) (seq argv)))
                   {:status :error :error "tool args must be {:argv [..]}, or {:code \"...\"} for a run_* tool"}
                   (if-let [guard (dump-guard argv)]
                     {:status :error :error guard}
                     (try
                       (let [p (ensure-pool!)
                           r (sb-pool-exec p (argv->cmd argv) :timeout-ms 120000 :cwd workspace :interrupt *interrupt*)]
                       (if (:interrupted? r)
                         {:status :error :error "interrupted" :interrupted? true}
                         (if (and (contains? #{126 127 255} (:exit r))
                                  (str/blank? (str (:stdout r)))
                                  (str/blank? (str (:stderr r))))
                           ;; 255 = ssh transport failure; 126/127 with empty
                           ;; output = the devShell layer dropped (nix-store
                           ;; binaries gone). Either way the VM is unusable:
                           ;; drop it so the next call boots fresh.
                           (do (recycle-pool!)
                               {:status :error
                                :error "sandbox VM unreachable (recycled); retry"
                                :fatal? false
                                :retryable? true})
                           {:status (if (zero? (:exit r)) :ok :error)
                            :exit (:exit r)
                            :stdout (:stdout r)
                            :stderr (:stderr r)})))
                     (catch Exception e
                       (let [msg (str "sandbox exec failed (" (or (ex-message e) "?") ")")
                             boot? (nil? (:pool @state))]
                         (if boot?
                           (swap! state assoc :failed? true :boot-error msg)
                           (recycle-pool!))
                         {:status :error :error msg :fatal? boot?}))))))))
     :warm! (fn []
              ;; boot the pool eagerly so faraday starts with a warm VM
              ;; instead of paying the boot (and any boot failure) on the
              ;; first tool call. Returns nil on success, else the error string.
              (try (ensure-pool!) nil
                   (catch Exception e
                     (let [msg (str "sandbox boot failed (" (or (ex-message e) "?") ")")]
                       (swap! state assoc :failed? true :boot-error msg)
                       msg))))
     :close (fn []
              (when-let [p (:pool @state)]
                (try (sb-close-pool! p) (catch Exception _ nil))))}))

(defn- repair-note []
  "Return a valid action: exactly one of a tool call or {:done \"...\"}.")

  ;; Bound by chat-session around run-turn!; model-step calls it with the
  ;; running thinking text on each streamed delta, and with "" to clear a
  ;; partial before a retry/escalation.
  (def ^:dynamic *on-delta* nil)

(defn- model-step
  "The chat's model step: native function calling (tools -> tool_calls) with a
  repair + escalate fallback. `primary` and `strong` are provider cfgs; returns
  a canonical action map ({:tool-call ..} {:tool-calls [..]} {:done ..}) or a
  plain-text string (never nil). On a null result it retries with repair
  guidance, then escalates to the strong provider. `usage` (a ratom) accumulates
  every billed response's token usage across retries via llm/add-usage."
  [primary strong messages usage]
  (let [on-delta *on-delta*
        try-cfg (fn [cfg extra clear?]
                  (let [msgs (if extra (conj messages {:role "user" :content extra}) messages)]
                    (when (and clear? on-delta) (on-delta ""))
                    (try
                      (let [cfg (cond-> (assoc cfg :tools (tools/openai-tools))
                                  on-delta (assoc :stream true))
                            r (llm/completion->result cfg msgs on-delta)]
                        (swap! usage llm/add-usage (:usage r))
                        (:action r))
                      (catch Exception _ nil))))]
    (or (try-cfg primary nil false)
        (try-cfg primary (repair-note) true)
        (try-cfg strong (str (repair-note) " Escalate: reason carefully.") true)
        "")))

(defn- with-circuit-breaker
  "Wrap a model `f` so N (default 3) consecutive give-ups (blank strings) open a
  circuit: later calls short-circuit to a :done error instead of re-calling the
  model. A non-blank result resets the failure count."
  [breaker f]
  (fn [x]
    (if (:open? @breaker)
      {:done (str "circuit open: " (:failures @breaker) " consecutive model failures")}
      (let [res (f x)]
        (if (and (string? res) (str/blank? res))
          (let [n (inc (:failures @breaker))]
            (swap! breaker assoc :failures n :open? (>= n 3))
            (if (>= n 3)
              {:done (str "circuit open after " n " model failures — could not answer")}
              res))
          (do (swap! breaker assoc :failures 0)
              res))))))

(defn- force-final-answer!
  "The turn budget ran out or the agent stalled: make one more model call that is
  forbidden to use tools, and record its answer. The transcript never ends on a
  bare 'max tool turns' error — the operator always gets the best answer that
  could still be produced."
  [call system es state reason]
  (let [msgs (conj (build-messages system es)
                   {:role "user"
                    :content (str reason
                                  " Give your best final answer now, as exactly one EDN map "
                                  "{:done \"...\"} or plain text; do not call tools.")})
        resp (try (call msgs) (catch Exception _ nil))
        action (if (string? resp) (parse-action resp) resp)
        done? (and action (:done action))
        tool? (or (:tool-call action) (:tool-calls action))
        text (cond
               done? (let [d (:done action)] (if (string? d) d (pr-str d)))
               (and (not tool?) (string? resp) (seq (str/trim resp))) (str/trim resp)
               :else "I ran out of tool turns before reaching an answer.")]
    (swap! state update :messages conj {:role :assistant :text text})
    (swap! state assoc :busy? false :error nil)
    text))

(defn- truncate-tool-out
  "Cap a tool's output so a whole-file dump cannot flood the model's context.
  The marker tells the model to re-read the needed part with sed/grep."
  [s]
  (let [s (str s)
        lines (str/split-lines s)
        max-lines 80
        max-chars 10000]
    (cond
      (> (count lines) max-lines)
      (str (str/join "\n" (take max-lines lines))
           "\n… [truncated " (- (count lines) max-lines) " more lines — use sed -n / grep -n for the part you need]")
      (> (count s) max-chars)
      (str (subs s 0 max-chars)
           "\n… [truncated — use sed -n / grep -n for the part you need]")
      :else s)))

(defn run-turn!
  "Drive one agentic turn: loop model call -> tool exec until the model answers.
  opts: :call (fn messages -> content), :exec (fn name args -> result), :system,
  :max-turns, :max-stall (consecutive no-progress tool steps before stopping).
  Appends entries to `state` and clears :busy?; on a fault or stall sets :error.
  Safe to run on a worker thread (only ratom swaps, no widget touches)."
  [opts state entries]
  (let [{:keys [call exec system max-turns max-stall goal-fn context-budget guard]} opts
        interrupt (or *interrupt* (:interrupt opts) (atom false))
        max-stall (or max-stall 3)
        context-budget (or context-budget 40)
        run-one (fn [name args es]
                  (let [attempt (fn [res]
                                  [{:role :tool-call :name name :args args}
                                   {:role :tool-result :name name
                                    :status (:status res)
                                    :exit (:exit res)
                                    :text (truncate-tool-out (or (:stdout res) (:stderr res) ""))
                                    :error (:error res)
                                    :fatal? (true? (:fatal? res))
                                    :interrupted? (true? (:interrupted? res))
                                    :progress? (if (contains? res :progress?)
                                                 (:progress? res)
                                                 (= :ok (:status res)))}])
                        res1 (if-let [refusal (and guard (guard {:name name :args args} es))]
                               {:status :error :error refusal}
                               (exec name args))]
                    (if (and (true? (:retryable? res1))
                             (not (:interrupted? res1)))
                      ;; the sandbox recycled itself; replay once on the fresh
                      ;; VM and record both attempts so the trace shows the
                      ;; recycle + retry instead of a bare error
                      (vec (concat (attempt res1) (attempt (exec name args))))
                      (attempt res1))))
        budget-note (fn [left]
                       (cond
                         (<= left 2)
                         "Only 2 tool turns left: do not read any more files — make the edit (or give the answer) now with what you already have."
                         (<= left 4)
                         "A few tool turns left: start editing now; only read a file if one targeted read unblocks the edit."
                         :else nil))
        step! (fn [es left]
                (let [msgs (build-messages system es)
                      msgs (if-let [note (budget-note left)]
                             (conj msgs {:role "user" :content note})
                             msgs)
                      resp (call msgs)
                      action (if (string? resp) (parse-action resp) resp)
                      tcs (cond
                            (:tool-call action) [(:tool-call action)]
                            (:tool-calls action) (:tool-calls action)
                            :else nil)]
                  (cond
                    ;; one or several tool calls (native tool_calls, EDN, or
                    ;; DSML): execute each, feed results back, loop
                    (seq tcs)
                    (let [entries (vec (mapcat (fn [tc] (run-one (:name tc) (:args tc) es)) tcs))
                          fatal (some (fn [e] (when (:fatal? e) (or (:error e) "fatal tool error"))) entries)
                          interrupted (boolean (some :interrupted? entries))]
                      (swap! state update :messages into entries)
                      {:cont (into es entries)
                       :progress? (boolean (some :progress? entries))
                       :fatal fatal
                       :interrupted interrupted})

                    ;; done: render the answer and stop
                    (and action (:done action))
                    (let [d (:done action)
                          text (if (string? d) d (pr-str d))]
                      (swap! state update :messages conj {:role :assistant :text text})
                      :done)

                    ;; plain text answer (no action map): render and stop
                    :else
                    (do
                      (swap! state update :messages conj {:role :assistant :text (str (or resp ""))})
                      :done))))]
    (loop [left (or max-turns 96)
           stall 0
           es entries]
      (cond
        (and goal-fn (goal-fn {:entries es :state @state}))
        (force-final-answer! call system es state
          "The goal is met — give your final answer now.")

        (> (count es) (* 3 context-budget))
        (force-final-answer! call system es state
          "Your context is full — give your best final answer now.")

        (zero? left)
        (force-final-answer! call system es state
          "You have used every tool turn.")

        (>= stall max-stall)
        (force-final-answer! call system es state
          "Your last tools made no progress.")

        @interrupt
        (do (swap! state update :messages conj {:role :assistant :text "Stopped."})
            (swap! state assoc :busy? false :error nil))

        :else
        (let [outcome (try (step! es left)
                           (catch Exception e
                             {:fault (or (ex-message e) "turn failed")}))]
          (cond
            (:fault outcome) (swap! state assoc :busy? false :error (:fault outcome))
            (= :done outcome) (swap! state assoc :busy? false)
            (:fatal outcome)
            (let [msg (:fatal outcome)]
              ;; fail fast: the turn ends with a final assistant answer carrying
              ;; the fatal error, not a bare :error with no answer.
              (swap! state update :messages conj {:role :assistant :text msg})
              (swap! state assoc :busy? false :error msg))
            (:cont outcome)
            (if (:interrupted outcome)
              (do (swap! state update :messages conj {:role :assistant :text "Stopped."})
                  (swap! state assoc :busy? false :error nil))
              (recur (dec left)
                     (if (:progress? outcome) 0 (inc stall))
                     (:cont outcome)))
            :else            (swap! state assoc :busy? false)))))))

;; -- the UI -------------------------------------------------------------------

(defn- root-widget
  "The live root widget atom, or nil, from the running glimmer-tui app. The
  transcript scroll lives inside it; this is only for pinning that scroll to the
  bottom and fails closed."
  []
  (:root @@#'glimmer-tui.core/app))

(defn- find-scroll-widget
  [w]
  (when w
    (if (= :scroll (:tag @w))
      w
      (some find-scroll-widget (:children @w)))))

(defn- scroll-to-bottom!
  "Pin the transcript scroll to the bottom. Every caller runs on the loop thread
  (the every! timer, send!, the pending flush), so the mutation is done inline
  rather than scheduled. Setting :y-offset to a huge number is enough:
  arrange-scroll clamps it to the fresh max offset at layout time, so this never
  needs the current layout."
  []
  (when-let [sw (some-> (root-widget) find-scroll-widget)]
    (swap! sw update :state assoc :x-offset 0 :y-offset 2147483647)
    (w/touch!)))

(defn- transcript-scroll!
  "Scroll the transcript viewport by what `ev` implies. The composer entry is
  always focused, so arrow/page keys never reach the :scroll widget (a sibling);
  they bubble here instead and we drive the laid-out scroll node directly.
  Scrolling up, or Home, detaches `follow?` (stop auto-pinning); End, or
  scrolling down to the bottom, re-arms it."
  [ev follow?]
  (let [{:keys [tree root]} @@#'glimmer-tui.core/app]
    (when (and tree root)
      (when-let [n (some (fn [x] (when (= :scroll (w/container-kind (:tag x))) x))
                         (w/walk tree))]
        (when-let [wid (w/find-widget root (:id n))]
          (let [page (max 1 (dec (max 1 (:h (:viewport n) 0))))
                move
                (cond
                  (k/match? ev "up") [:by -1]
                  (k/match? ev "down") [:by 1]
                  (k/match? ev "pgup") [:by (- page)]
                  ;; Home jumps to the top and detaches: the operator wants to
                  ;; read the history
                  (k/match? ev "home") [:to-top]
                  ;; End jumps to the bottom and re-follows
                  (k/match? ev "end") [:to-bottom]
                  ;; PgDn pages while following; detached it jumps back to the
                  ;; bottom and re-follows, instead of paging screen by screen
                  (k/match? ev "pgdn") (if @follow? [:by page] [:to-bottom])
                  :else nil)]
            (when move
              (let [kind (first move)
                    moved? (case kind
                             :by (scroll/scroll-by! wid n 0 (second move))
                             :to-top (scroll/scroll-to! wid n 0 0)
                             :to-bottom (scroll/scroll-to! wid n 0 2147483647))]
                (when (or (= :to-top kind)
                          (and (= :by kind) (neg? (second move))))
                  (reset! follow? false))
                (when (or (= :to-bottom kind)
                          (and (= :by kind) (pos? (second move)) (not moved?)))
                  (reset! follow? true))))))))))

  (defn- live-cols
  "Live terminal columns: the mounted glimmer-tui screen when present (the
  authoritative size for the chat TUI — jolt.host/term-size reports the jolt
  REPL's terminal, not faraday's), else jolt.host/term-size, else nil."
  []
  (or (try (let [[c _] (scr/size (:screen @@#'glimmer-tui.core/app))]
             (when (pos? c) c))
           (catch Throwable _ nil))
      (try (let [[_ c] (jolt.host/term-size)] c)
           (catch Throwable _ nil))))

  (defn- wrap-width
  "Columns to wrap transcript text to. Live terminal width wins (minus the
  scrollbar column and a one-column inset); an explicit :wrap (--wrap flag or
  chat.wrap) is used literally; headless falls back to 92. `cols`, when given,
  is the live width the caller already measured."
  [opts & [cols]]
  (let [explicit (:wrap opts)
        live (or cols (live-cols))
        cols (or explicit live 92)]
    (max 24 (- (long cols) (if (and live (not explicit)) 2 0)))))

(defn markdown-segments
  "Split assistant markdown into render segments via jolt-markdown. Prose
  segments carry :spans (inline strong/em/strike/code/link) plus the plain
  :text for previews and line counts; fenced code stays verbatim. Numbered
  items keep their number as a render prefix; task lists, rules and tables
  keep their block kind."
  [text]
  (mapv (fn [blk]
          (case (:type blk)
            :heading {:t :heading :kind :heading :level (:level blk)
                      :text (md/spans-text (:spans blk))
                      :spans (:spans blk)}
            :quote {:t :line :kind :quote
                    :text (md/spans-text (:spans blk))
                    :spans (:spans blk)}
            :bullet {:t :line :kind :bullet
                     :text (md/spans-text (:spans blk))
                     :spans (:spans blk)}
            :task {:t :line :kind :task
                   :checked? (:checked? blk)
                   :text (md/spans-text (:spans blk))
                   :spans (:spans blk)}
            :numbered {:t :line :kind :numbered :n (:n blk)
                       :text (md/spans-text (:spans blk))
                       :spans (:spans blk)}
            :hr {:t :hr}
            :table {:t :table :rows (:rows blk) :aligns (:aligns blk)}
            :text {:t :line :kind :text
                   :text (md/spans-text (:spans blk))
                   :spans (:spans blk)}
            :code {:t :code :lines (:lines blk)}))
        (md/parse-blocks (str text))))

(defn- markdown-lines
  "The per-line view of markdown-segments: [[kind text] ...], a code block
  flattening to :code lines. This is the simple highlighter shape the tests pin;
  the transcript renders markdown-segments directly."
  [text]
  (vec
   (mapcat (fn [seg]
             (case (:t seg)
               :code (map (fn [l] [:code l]) (:lines seg))
               :hr [[:hr ""]]
               :table [[:table (str/join " | " (map #(str/join " " %) (:rows seg)))]]
               (case (:kind seg)
                 :numbered [[:numbered (str (:n seg) ". " (:text seg))]]
                 [[(:kind seg) (:text seg)]])))
           (markdown-segments text))))

(defn- deref-thread
  "A session thread-id may be a plain string or an atom (the chat shell switches
  it on /fork); return the current value either way."
  [thread-id]
  (if (instance? clojure.lang.IDeref thread-id) @thread-id thread-id))

(def ^:private task-id-re #"faraday-[0-9a-f]{12}")

(defn- task-id-in
  "The faraday board-task id mentioned in `text`, or nil."
  [text]
  (re-find task-id-re (str text)))

(defn- task-done-goal
  "A :goal-fn for run-turn!: met when the transcript shows the board task was
  moved to done. Stops the loop at the real completion signal instead of
  waiting for the model to remember to answer."
  [task-id]
  (fn [{:keys [entries]}]
    (boolean
     (some (fn [e]
             (and (= :tool-call (:role e))
                  (= "task_set_status" (:name e))
                  (= task-id (:id (:args e)))
                  (= "done" (:status (:args e)))))
           entries))))

(defn chat-session
  "A headless chat: owns the transcript and drives the tool loop. Returns
  {:state (ratom {:messages :busy? :error}) :send! (fn text)
  :interrupt! (fn)}. The terminal TUI renders :state; the web console can
  render and drive the same :state, so both clients see one conversation.
  interrupt! asks a running turn to stop at the next step boundary."
  [{:keys [call exec system max-turns max-stall stores thread-id classify]}]
  (let [state (r/atom {:messages (or (when (and stores thread-id)
                                      (try (session/resume stores (deref-thread thread-id))
                                           (catch Exception _ nil)))
                                    [])
                       :busy? false :error nil :redo [] :thinking nil})
        interrupt (atom false)
        send! (fn [text]
                (let [text (str/trim (str text))]
                  (when (and (seq text) (not (:busy? @state)))
                    (let [es (conj (:messages @state) {:role :user :text text})
                          task-id (last (keep task-id-in
                                              (map :text
                                                   (filter #(= :user (:role %)) es))))]
                      (reset! interrupt false)
                      (swap! state assoc :busy? true :error nil :messages es :redo [])
                      (future
                        (binding [*interrupt* interrupt
                                  *on-delta* (fn [t] (swap! state assoc :thinking t))]
                          (run-turn! {:call call :exec exec :system system
                                      :max-turns max-turns :max-stall max-stall
                                      :interrupt interrupt
                                      :goal-fn (when task-id (task-done-goal task-id))
                                      :guard (fn [tc entries]
                                               (or (guard/verdict tc entries)
                                                   (when classify
                                                     (let [name (str (:name tc))
                                                           text (cond
                                                                  (contains? #{"run_shell" "shell" "exec"} name)
                                                                  (str/join " " (map str (get-in tc [:args :argv])))
                                                                  (re-matches #"run_[a-z0-9-]+" name)
                                                                  (str (get-in tc [:args :code]))
                                                                  :else nil)]
                                                       (when text
                                                         (when-let [p (classify text)]
                                                           (cond
                                                             (>= (:dangerous p) 0.5)
                                                             (str "Blocked (flash): dangerous — " (format "%.0f%%" (* 100 (:dangerous p))) ".")
                                                             (and (>= (:mutating p) 0.5) (not (guard/claimed? entries)))
                                                             (str "Requires a claimed task (flash: mutating — " (format "%.0f%%" (* 100 (:mutating p))) ").")
                                                             :else nil)))))))}

                                     state es)
                        (swap! state assoc :thinking nil))
                        (when (and stores thread-id)
                          (try (session/persist! stores (deref-thread thread-id) (:messages @state))
                               (catch Exception _ nil))))))))]
    {:state state :send! send! :interrupt! (fn [] (reset! interrupt true))}))

(defn entry-segments
  "One conversation entry as render segments. `wrap` is the prose column width;
  code hard-wraps to the same width without reflowing."
  [e wrap]
  (case (:role e)
    :user
    (map (fn [line] {:t :line :role :user :kind :text :text line})
         (word-wrap (:text e) wrap))

    :assistant
    (mapcat
     (fn [seg]
       (case (:t seg)
         :code [{:t :code :role :assistant
                 :lines (vec (mapcat #(hard-wrap % wrap) (:lines seg)))}]
         :hr [{:t :hr :role :assistant}]
         :table [{:t :table :role :assistant
                  :rows (:rows seg) :aligns (:aligns seg) :width wrap}]
         :heading [{:t :heading :role :assistant :level (or (:level seg) 1)
                    :lines (md/wrap-spans (:spans seg) wrap)}]
         (let [lines (md/wrap-spans (:spans seg) wrap)
               kind (:kind seg)]
           (if (empty? lines)
             ;; a blank markdown line stays one blank row, so paragraphs keep
             ;; their separation while adjacent blocks (a tight list) touch
             [{:t :spacer}]
             (cond
               (= :bullet kind)
               (map-indexed
                (fn [i line]
                  {:t :line :role :assistant
                   :kind (if (zero? i) :bullet :bullet-cont)
                   :text (md/spans-text line)
                   :spans line})
                lines)
               (= :task kind)
               (map-indexed
                (fn [i line]
                  (cond-> {:t :line :role :assistant
                           :kind (if (zero? i) :task :task-cont)
                           :text (md/spans-text line)
                           :spans line}
                    (zero? i) (assoc :checked? (:checked? seg))))
                lines)
               (= :numbered kind)
               (map-indexed
                (fn [i line]
                  {:t :line :role :assistant
                   :kind (if (zero? i) :numbered :numbered-cont)
                   :num (:n seg)
                   :text (md/spans-text line)
                   :spans line})
                lines)
               :else
               (map (fn [line] {:t :line :role :assistant :kind kind
                                :text (md/spans-text line)
                                :spans line})
                    lines))))))
     (markdown-segments (:text e)))

    :tool-call
    (map (fn [line] {:t :line :role :tool-call :kind :text :text line})
         (word-wrap (let [argv (seq (:argv (:args e)))
                          txt (str (or (:name e) "")
                                   (when argv (str " " (str/join " " (map str argv)))))]
                      (if (str/blank? txt) "tool-call" txt))
                    wrap))

    :tool-result
    (map (fn [line] {:t :line :role :tool-result :kind :text :text line})
         (word-wrap (format-tool-result
                     (select-keys e [:name :status :exit :error :text]))
                    wrap))

    ;; unknown roles read as plain assistant text
    (map (fn [line] {:t :line :role :assistant :kind :text :text line})
         (word-wrap (str (:text e)) wrap))))

(defn transcript-segments
  "The whole conversation as render segments; blank prose lines are dropped so
  empty model turns don't pad the transcript with dead space."
  [messages wrap]
  (->> messages
       (mapcat #(entry-segments % wrap))
       (remove (fn [seg]
                 (or (= :spacer (:t seg))
                     (and (= :line (:t seg))
                          (str/blank? (str (:text seg)))))))
       vec))

  (defn- line-style
  "Colour/style props for one transcript line."
  [role kind]
  (case role
    :user {:color (themes/color :user) :bold true}
    :assistant (case kind
                 :heading {:color (themes/color :heading) :bold true}
                 :quote {:color (themes/color :muted) :dim true}
                 :bullet {:color (themes/color :body)}
                 {:color (themes/color :body)})
    :tool-call {:color (themes/color :tool) :bold true}
    :tool-result {:color (themes/color :muted) :dim true}
    {:color (themes/color :body)}))

(defn- line-prefix
  ([role kind] (line-prefix role kind nil nil))
  ([role kind checked?] (line-prefix role kind checked? nil))
  ([role kind checked? num]
   (case role
     :user "you> "
     :tool-call "  → "
     :tool-result "    "
     :assistant (case kind
                  :heading "▍ "
                  :quote "│ "
                  :bullet "  • "
                  :bullet-cont "    "
                  :task (if checked? "  ☑ " "  ☐ ")
                  :task-cont "    "
                  :numbered (str "  " num ". ")
                  :numbered-cont (apply str (repeat (+ 4 (count (str num))) " "))
                  "")
     "")))

(defn- span-props
  "Style props for one inline span: the line's own style by default, overlaid
  with the span's emphasis. glimmer-tui has no italic or strikethrough, so em
  renders underlined and strikethrough renders dim (the terminal conventions)."
  [base {:keys [style]}]
  (let [style (or style #{})]
    (cond-> base
      (:strong style) (assoc :bold true :color (themes/color :strong))
      (:em style)     (assoc :underline true)
      (:strike style) (assoc :dim true)
      (:code style)   (assoc :color (themes/color :inline-code))
      (:link style)   (assoc :color (themes/color :link) :underline true))))

(defn- spans-hiccup
  "A prose line as hiccup: an optional prefix label (bullet/heading/quote
  marker) followed by one label per span, so inline styles render per-span."
  [role kind prefix spans]
  (let [base (line-style role kind)]
    (if (and (empty? prefix) (= 1 (count spans)))
      (let [s (first spans)]
        [:label (assoc (span-props base s) :label (:text s))])
      (into [:hbox {:spacing 0}]
            (concat
             (when (seq prefix)
               [[:label (assoc base :label prefix)]])
             (map (fn [s]
                    [:label (assoc (span-props base s) :label (:text s))])
                  spans))))))

(defn- pad-cell
  [s w align]
  (let [s (str s)
        pad (max 0 (- w (count s)))]
    (case align
      :right (str (apply str (repeat pad " ")) s)
      :center (let [l (quot pad 2) r (- pad l)]
                (str (apply str (repeat l " ")) s (apply str (repeat r " "))))
      (str s (apply str (repeat pad " "))))))

(defn- table-lines
  "Aligned text lines for a markdown table's :rows/:aligns, no wider than
  `width`. Line 0 is the header, line 1 the divider."
  [rows aligns width]
  (let [rows (vec rows)
        ncol (apply max 1 (map count rows))
        aligns (vec (take ncol (concat aligns (repeat :left))))
        maxw (max 6 (- width 6))
        desired (mapv (fn [c]
                        (min maxw (max 4 (apply max 4 (map #(count (str (nth % c ""))) rows)))))
                      (range ncol))
        seps (* 2 (max 0 (dec ncol)))
        colw (if (<= (+ (reduce + desired) seps) width)
               desired
               (loop [ws desired budget (- (+ (reduce + desired) seps) width)]
                 (if (or (zero? budget) (every? #(<= % 4) ws))
                   ws
                   (let [i (first (apply max-key second (map-indexed vector ws)))]
                     (recur (update ws i dec) (dec budget))))))
        cell-lines (mapv (fn [row]
                           (mapv (fn [c] (hard-wrap (str (nth row c "")) (colw c)))
                                 (range ncol)))
                         rows)
        row-h (mapv (fn [cells] (apply max 1 (map count cells))) cell-lines)
        align-cell (fn [s c] (pad-cell s (colw c) (nth aligns c :left)))
        render-row (fn [cells h]
                     (mapv (fn [ln]
                             (str/join "  "
                                       (map-indexed (fn [c _]
                                                      (align-cell (nth (nth cells c) ln "")
                                                                  c))
                                                    (range ncol))))
                           (range h)))
        divider (str/join "  " (mapv (fn [c] (apply str (repeat (colw c) "─"))) (range ncol)))]
    (vec (concat
          (render-row (first cell-lines) (row-h 0))
          [divider]
          (mapcat (fn [i] (render-row (cell-lines i) (row-h i)))
                  (range 1 (count cell-lines)))))))

  (defn- table-hiccup
  [seg]
  (let [lines (table-lines (:rows seg) (:aligns seg) (:width seg))]
    [:box {:bg (themes/color :code-slab) :hexpand true}
     (into [:vbox {:spacing 0 :padding [0 1 0 1]}]
           (map-indexed
            (fn [i line]
              [:label {:label line
                       :color (cond
                                (zero? i) (themes/color :heading)
                                (= 1 i) (themes/color :muted)
                                :else (themes/color :inline-code))
                       :bold (zero? i)}])
            lines))]))

  (defn segment-hiccup
  "One render segment as hiccup."
  [seg]
  (case (:t seg)
    :code [:box {:bg (themes/color :code-slab) :hexpand true}
           (into [:vbox {:spacing 0 :padding [0 1 0 1]}]
                 (map (fn [line]
                        [:label {:label line :color (themes/color :inline-code)}])
                      (:lines seg)))]
    :hr [:separator {:char "─" :color (themes/color :muted) :hexpand true}]
    :table (table-hiccup seg)
    :spacer [:label {:label " "}]
    :heading [:box {:bg (themes/heading-band (:level seg)) :hexpand true}
              (into [:vbox {:spacing 0 :padding [0 1 0 1]}]
                    (map (fn [line]
                           (spans-hiccup :assistant :heading "" line))
                         (:lines seg)))]
    (let [{:keys [role kind text spans checked? num]} seg]
      (if (and (= role :assistant) (seq spans))
        (spans-hiccup role kind (line-prefix role kind checked? num) spans)
        [:label (assoc (line-style role kind)
                       :label (str (line-prefix role kind checked? num) text))]))))

;; -- folding -----------------------------------------------------------------
;; Tool results and long code blocks are the bulky, low-signal parts of a
;; transcript, so they fold to a one-line header; Enter on the header (or a
;; click) toggles the body. `expanded` holds the set of keys the operator has
;; opened — a key is the entry index, or [entry-index :code code-index]. Tool
;; results are folded by default; short code blocks stay open.

(defn- toggle-expanded! [expanded k]
  (swap! expanded (fn [s] (if (contains? s k) (disj s k) (conj s k)))))

(defn- fold-header
  "A button that toggles fold key `k`; `open?` picks the glyph."
  [expanded k open? label]
  [:button {:label (str (if open? "▾ " "▸ ") label)
            :color (themes/color :muted)
            :on-click #(toggle-expanded! expanded k)}])

(defn- tool-result-summary
  "One line for a folded tool result: name, status, exit, and line count."
  [e]
  (let [lines (count (str/split-lines (str (or (:text e) ""))))]
    (str "tool " (:name e) " · " (name (or (:status e) :?))
         (when (some? (:exit e)) (str " · exit " (:exit e)))
         " · " lines " lines")))

(defn- tool-result-body
  "The tool's stdout as a code slab, verbatim; the header already says what it is."
  [e wrap]
  (let [lines (->> (str/split-lines (str (or (:text e) "")))
                   (mapcat #(hard-wrap % wrap)))]
    [:box {:bg (themes/color :code-slab) :hexpand true}
     (into [:vbox {:spacing 0 :padding [0 1 0 1]}]
           (map (fn [line] [:label {:label line :color (themes/color :inline-code)}]) lines))]))

(defn- seg-line-count
  "Rendered line count of entry-segments output: prose segments are one line
  each, a code segment counts its verbatim lines."
  [segs]
  (reduce (fn [n seg] (+ n (if (contains? #{:code :heading} (:t seg)) (count (:lines seg)) 1))) 0 segs))

(defn- assistant-summary
  "Fold header for a long assistant answer: its line count and a one-line
  preview of its prose."
  [segs lines]
  (let [preview (some (fn [s] (when (= :line (:t s)) (str/trim (:text s)))) segs)
        preview (when (and preview (seq preview))
                  (if (> (count preview) 40) (str (subs preview 0 40) "…") preview))]
    (str "assistant · " lines " lines" (when preview (str " · " preview)))))

(defn- group-tool-steps
  "Pair each :tool-call with the :tool-result that immediately follows it into
  one {:role :tool-step :call .. :result ..} entry; a call or result with no
  pair is left as-is. Greedy left-to-right, so earlier pairs keep their
  position as the transcript appends."
  [messages]
  (loop [ms (seq messages) out []]
    (if-not ms
      (vec out)
      (let [e (first ms)
            r (second ms)]
        (if (and (= :tool-call (:role e))
                 (= :tool-result (:role r))
                 (= (:name e) (:name r)))
          (recur (nnext ms) (conj out {:role :tool-step :call e :result r}))
          (recur (next ms) (conj out e)))))))

(defn- tool-step-summary
  "Fold header for one tool step: tool name, a clipped view of the argv, then
  status/exit/line-count. The command leads so it stays readable while the
  output is folded."
  [call result]
  (let [tool-name (str (:name call))
        argv (seq (:argv (:args call)))
        cmd (when argv (str/join " " (map str argv)))
        cmd (when (and cmd (seq cmd))
              (if (> (count cmd) 40) (str (subs cmd 0 40) "…") cmd))
        lines (count (str/split-lines (str (or (:text result) ""))))]
    (str "→ " tool-name
         (when cmd (str " " cmd))
         " · " (name (or (:status result) :?))
         (when (some? (:exit result)) (str " · exit " (:exit result)))
         " · " lines " lines")))

(defn- entry-hiccup
  "One conversation entry as hiccup: tool steps, tool results, long code blocks
  and long assistant prose are all foldable (collapsed to a header; Enter/click
  opens). The latest assistant answer stays open so the operator reads it whole;
  only earlier long answers fold. `i` is the entry index — stable because
  messages only append."
  [expanded wrap last-assistant? i e]
  (case (:role e)
    :tool-step
    (let [{:keys [call result]} e
          open? (contains? @expanded i)]
      [:vbox {:spacing 0}
       (fold-header expanded i open? (tool-step-summary call result))
       (when open?
         [:vbox {:spacing 0}
          (into [:vbox {:spacing 0}]
                (map segment-hiccup (entry-segments call wrap)))
          (tool-result-body result wrap)])])

    :tool-result
    (let [open? (contains? @expanded i)]
      [:vbox {:spacing 0}
       (fold-header expanded i open? (tool-result-summary e))
       (when open? (tool-result-body e wrap))])

    :assistant
    (let [segs (entry-segments e wrap)
          prose-lines (count (remove #(= :code (:t %)) segs))]
      (if (and (> prose-lines 8) (not last-assistant?))
        ;; an older long answer collapses whole; the latest one stays open to read
        (let [k [i :assistant]
              open? (contains? @expanded k)]
          [:vbox {:spacing 0}
           (fold-header expanded k open? (assistant-summary segs (seg-line-count segs)))
           (when open?
             (into [:vbox {:spacing 0}] (map segment-hiccup segs)))])
        (into [:vbox {:spacing 0}]
              (map-indexed
               (fn [ci seg]
                 (if (and (not last-assistant?)
                         (= :code (:t seg))
                         (> (count (:lines seg)) 12))
                   (let [k [i :code ci]
                         open? (contains? @expanded k)]
                     [:vbox {:spacing 0}
                      (fold-header expanded k open? (str "code · " (count (:lines seg)) " lines"))
                      (when open? (segment-hiccup seg))])
                   (segment-hiccup seg)))
               segs))))

    :user
    [:box {:bg (themes/color :user-block) :hexpand true}
     (into [:vbox {:spacing 0 :padding [0 1 0 1]}]
           (map segment-hiccup (entry-segments e wrap)))]

    (into [:vbox {:spacing 0}]
          (map segment-hiccup (entry-segments e wrap)))))

;; -- clipboard (OSC 52) ------------------------------------------------------
;; Native terminal copy (drag-select / Ctrl+Shift+C) works again because faraday
;; runs glimmer-tui with :mouse? false. This is the explicit copy: Ctrl+Y yanks
;; the last answer onto the terminal clipboard through OSC 52, which most
;; terminals honour and the rest ignore silently.

(def ^:private b64-scheme
  "(define (faraday-b64 s)
  (let* ((bv (string->utf8 s))
         (n (bytevector-length bv))
         (tbl (list->string (map integer->char
                                  (append (map (lambda (x) (+ 65 x)) (iota 26))
                                          (map (lambda (x) (+ 97 x)) (iota 26))
                                          (map (lambda (x) (+ 48 x)) (iota 10))
                                          (list 43 47)))))
         (out (open-output-string)))
    (let loop ((i 0))
      (if (>= i n)
          (get-output-string out)
          (let* ((a (bytevector-u8-ref bv i))
                 (b (if (< (+ i 1) n) (bytevector-u8-ref bv (+ i 1)) 0))
                 (c (if (< (+ i 2) n) (bytevector-u8-ref bv (+ i 2)) 0))
                 (x (+ (ash a 16) (ash b 8) c)))
            (write-char (string-ref tbl (bitwise-and (ash x -18) 63)) out)
            (write-char (string-ref tbl (bitwise-and (ash x -12) 63)) out)
            (write-char (if (< (+ i 1) n) (string-ref tbl (bitwise-and (ash x -6) 63)) (integer->char 61)) out)
            (write-char (if (< (+ i 2) n) (string-ref tbl (bitwise-and x 63)) (integer->char 61)) out)
            (loop (+ i 3)))))))")

(def ^:private faraday-b64
  (do (jolt.host/scheme-eval-string b64-scheme)
      (jolt.host/scheme-proc "faraday-b64")))

(defn- copy-sequence
  "The OSC 52 sequence that puts `text` on the terminal clipboard."
  [text]
  (str "\u001b]52;c;" (faraday-b64 (str text)) "\u0007"))

(defn- emit-clipboard!
  "Write `text` to the terminal clipboard; a terminal that does not speak OSC 52
  ignores the sequence."
  [text]
  (print (copy-sequence text))
  (flush))

(defn system-with-context
  "The chat system prompt: `base` (the tool contract) plus the operator's
  injected context lines and remembered facts. Pure; chat-app calls it, tests
  pin it."
  [base context-lines facts]
  (cond-> base
    (seq context-lines)
    (str "\n\nContext:\n" (str/join "\n" context-lines))
    (seq facts)
    (str "\n\nYou remember these facts from earlier sessions:\n" (pr-str facts))))

  (defn- transcript-view
  "The transcript vbox hiccup for `messages`. `thinking` is the live reasoning
  text, appended as a synthetic :thinking entry (never persisted, cleared when
  the real message lands). Pure in its args, so a cached caller can reuse the
  same hiccup object while the transcript is unchanged."
  [messages thinking expanded wrap]
  (let [grouped (cond-> (vec (group-tool-steps messages))
                  (seq thinking) (conj {:role :thinking :text thinking}))
        last-assistant-idx (last (keep-indexed (fn [i e] (when (= :assistant (:role e)) i)) grouped))]
    (into [:vbox {:spacing 0 :padding [1 2 1 1]}]
          (if (empty? grouped)
            [[:label {:label "Type a task; faraday runs tools and answers." :dim true}]]
            (map-indexed (fn [i e] (entry-hiccup expanded wrap (= i last-assistant-idx) i e)) grouped)))))

(defonce ^:private transcript-cache (atom nil))
(defonce ^:private transcript-key (atom nil))

(defn- transcript-view-cached
  "Single-entry memo over transcript-view: keyed by [messages @expanded wrap],
  so an unchanged transcript returns the identical hiccup object."
  [messages thinking expanded wrap]
  (let [k [messages thinking @expanded wrap]]
    (if (= @transcript-key k)
      @transcript-cache
      (let [h (transcript-view messages thinking expanded wrap)]
        (reset! transcript-key k)
        (reset! transcript-cache h)
        h))))

(defn command-dropdown
  "The / command picker drawn above the composer: up to `max-rows` matching
  commands, the selected row highlighted, and a footer of picker keys. Pure over
  the visible slice from commands/visible-window, so the selection stays pinned
  instead of spilling past a short terminal."
  [matches selected]
  (let [max-rows 8
        [start n] (cmd/visible-window matches selected max-rows)
        shown (subvec matches start (+ start n))]
    [:vbox {:bg (themes/color :canvas) :margin [0 1 0 0] :spacing 0}
     (if (seq shown)
       (into [:vbox {:spacing 0}]
             (map-indexed
              (fn [i c]
                (let [sel? (= (+ start i) selected)
                      name-color (if sel? (themes/color :accent) (themes/color :body))]
                  [:hbox {:hexpand true :spacing 2 :padding [0 1]}
                   [:hbox {:spacing 0}
                    [:label {:label " "
                             :bg (if sel? (themes/color :selection) (themes/color :canvas))}]
                    [:label {:label (:matched c)
                             :color name-color
                             :bold sel?
                             :bg (themes/color :canvas)}]]
                   [:label {:label (:description c)
                            :color (themes/color :chrome)
                            :halign :end :hexpand true
                            :bg (themes/color :canvas)}]]))
              shown))
       [:label {:label "  no matching command" :color (themes/color :muted) :dim true}])
     [:hbox {:padding [0 1]}
      [:label {:label (str (count matches) " command"
                           (when (not= 1 (count matches)) "s"))
               :color (themes/color :muted) :dim true}]
      [:label {:label "↑↓ choose · tab complete · enter run · esc close"
               :color (themes/color :muted) :dim true :halign :end :hexpand true}]]]))

(defn summarize-transcript
  "Compress a transcript string into a short standing summary via a summarizer
  cfg (base-url/api-key/model). Low effort; returns the trimmed summary text.
  When `usage` (a ratom) is supplied, the summarizer call's token usage is
  accumulated into it, so /cost counts compaction too. Throws on
  transport/model failure so callers can flash the error."
  [cfg transcript & [usage]]
  (let [resp (json/read-str
              (llm/curl-http
               (assoc cfg :reasoning "low")
               [{:role "user"
                 :content (str "Compress the faraday session transcript below into a short "
                               "standing summary: what the operator asked, what was done, "
                               "what remains open. Answer with only the summary, no preamble.\n\n"
                               transcript)}])
              :key-fn keyword)]
    (when usage
      (swap! usage llm/add-usage (llm/response-usage resp)))
    (str/trim (str (get-in resp [:choices 0 :message :content])))))

;; -- footer -------------------------------------------------------------------

(defn human-tokens
  "1234 -> \"1.2k\"; under 1000 stays as-is, and a trailing .0 is stripped."
  [n]
  (let [n (long (or n 0))]
    (if (>= n 1000)
      (let [s (format "%.1f" (/ n 1000.0))]
        (str (if (str/ends-with? s ".0") (subs s 0 (- (count s) 2)) s) "k"))
      (str n))))

(defn shorten-home
  "~/.x for paths under the user's home; any other path unchanged."
  [p]
  (let [p (str (or p ""))
        home (System/getProperty "user.home")]
    (if (and (seq home) (str/starts-with? p home))
      (str "~" (subs p (count home)))
      p)))

(defn usage-readout
  "One-line token usage with the cache share: \"↑1.2k(310c) ↓480\". The (cached)
  part appears only when the provider reported a cache read."
  [usage]
  (let [u (or usage {})
        p (long (or (:prompt_tokens u) 0))
        c (long (or (:completion_tokens u) 0))
        cached (long (or (:cached-input-tokens u) 0))]
    (str "↑" (human-tokens p)
         (when (pos? cached) (str "(" (human-tokens cached) "c)"))
         " ↓" (human-tokens c))))

(defn ctx-readout
  "The context estimate against its window: \"ctx 24k/64k 37%\", \"ctx 24k/?\"
  when the window is unproven, \"ctx 24k/≥12k\" when only a floor is known."
  [est]
  (let [e (or est {})
        est-tokens (human-tokens (:estimated e))
        budget (:budget e)
        floor (:floor e)]
    (cond
      (and (number? budget) (pos? budget))
      (str "ctx " est-tokens "/" (human-tokens budget) " "
           (long (Math/round (* 100 (/ (double (or (:estimated e) 0)) (double budget))))) "%")
      (and (number? floor) (pos? floor))
      (str "ctx " est-tokens "/≥" (human-tokens floor))
      :else
      (str "ctx " est-tokens "/?"))))

(defn footer-readout
  "One bottom status line: git branch · thread · cwd · usage · context · model.
  Pure over already-resolved fields; nil branch/thread/cwd drop out."
  [{:keys [branch thread-id cwd usage ctx model]}]
  (str/join "  "
            (filter seq
                    [(when branch (str branch))
                     (when thread-id
                       (str "#" (session/short-thread-id thread-id)))
                     (when cwd (shorten-home cwd))
                     (usage-readout usage)
                     (ctx-readout ctx)
                     (when model (str model))])))

(defn chat-app
  "The agentic chat component. `opts` accepts :system, :wrap, :model, :base-url,
  :api-key (the latter three override config/resolve-chat), :max-turns, and the
  two test seams :call (fn messages -> content) and :exec (fn name args ->
  result-map) that replace the real model and tool executor. Returns the
  component fn to hand to ui/run."
  [opts]
  (let [full-cfg (config/load-config opts)
        cfg (r/atom (config/resolve-chat full-cfg opts))
        strong (config/resolve-provider full-cfg :deepseek-pro opts)
        cols (r/atom nil)
        breaker (atom {:failures 0 :open? false})
        usage (r/atom {:prompt_tokens 0 :completion_tokens 0 :total_tokens 0 :requests 0})
        ctx-cache (atom nil)
        call (or (:call opts)
                 (with-circuit-breaker breaker #(model-step @cfg strong % usage)))
        exec (or (:exec opts) host-exec)
        ;; flash classifier is live only on the real model path; a test
        ;; :call seam means a fake model, so stay hermetic (regex floor only)
        classify (when-not (:call opts)
                   (fn [text] (jev/probabilities @cfg text)))
        max-turns (or (:max-turns opts) 96)
        stores (:session opts)
        thread-id (:thread-id opts)
        thread-ref (atom thread-id)
        cwd (or (:cwd opts) (System/getProperty "user.dir"))
        branch (git-branch cwd)
        proj-root (:proj-root opts)
        harness (:harness opts)
        board (:board opts)
        board-ready? (boolean (and board harness))
        facts (when (and stores thread-id)
                (try (session/salient stores thread-id) (catch Exception _ nil)))
        system (system-with-context
                (or (:system opts) agentic-system)
                (cond-> []
                  (:boot-context opts) (into (:boot-context opts))
                  (:sandbox-context opts) (conj (:sandbox-context opts))
                  (:context @cfg) (into (:context @cfg)))
                facts)
        sess (chat-session {:call call :exec exec :system system :max-turns max-turns
                            :stores stores :thread-id thread-ref :classify classify})
        state (:state sess)
        interrupt! (:interrupt! sess)
        pending? (r/atom false)
        follow? (r/atom true)
        last-messages (atom (:messages @state))
        was-busy? (atom (:busy? @state))
        draft (r/atom "")
        picker (r/atom {:open? false :selected 0})
        toast (r/atom nil)
        dialog (r/atom nil)
        settings-cfg (r/atom full-cfg)
        settings-path (or (:settings-file opts) (config/settings-file))
        hist-idx (r/atom nil)
        tick (r/atom 0)
        _ (tui/every! 100
                      (fn []
                        (when (:busy? @state) (swap! tick inc))
                        (let [c (or (live-cols) 92)]
                          (when (not= c @cols) (reset! cols c)))
                        (let [msgs (:messages @state)
                              busy? (:busy? @state)]
                          (when (and @follow? (not (identical? msgs @last-messages)))
                            (reset! last-messages msgs)
                            (scroll-to-bottom!))
                          ;; a round that just finished re-pins the transcript to
                          ;; the bottom and re-arms follow, so the fresh answer is
                          ;; always on screen even if the operator scrolled up
                          ;; while it was working
                          (when (and @was-busy? (not busy?))
                            (reset! follow? true)
                            (scroll-to-bottom!))
                          (reset! was-busy? busy?))
                        (when (and @pending? (not (:busy? @state)))
                          (reset! pending? false)
                          (let [text (str/trim (str @draft))]
                            (when (seq text)
                              (reset! draft "")
                              (reset! hist-idx nil)
                              (scroll-to-bottom!)
                              ((:send! sess) text))))))
        expanded (r/atom #{})
        copied (r/atom nil)
        user-texts
        (fn [] (vec (keep (fn [e] (when (= :user (:role e)) (:text e))) (:messages @state))))
        recall!
        (fn [dir]
          (let [texts (user-texts)
                n (count texts)]
            (when (pos? n)
              (swap! picker assoc :open? false)
              (case dir
                :prev (let [i (if (nil? @hist-idx) (dec n) (max 0 (dec @hist-idx)))]
                        (reset! hist-idx i)
                        (reset! draft (get texts i)))
                :next (when (some? @hist-idx)
                        (let [i (inc @hist-idx)]
                          (if (>= i n)
                            (do (reset! hist-idx nil) (reset! draft ""))
                            (do (reset! hist-idx i) (reset! draft (get texts i))))))))))
        copy-last-answer!
        (fn []
          (let [text (or (last (keep (fn [e] (when (= :assistant (:role e)) (:text e))) (:messages @state)))
                         (last (keep (fn [e] (when (= :user (:role e)) (:text e))) (:messages @state)))
                         "")]
            (emit-clipboard! text)
            (reset! copied (str "copied " (count text) " chars"))
            (tui/after! 1500 #(reset! copied nil))))
        flash!
        (fn [msg]
          (reset! toast msg)
          (tui/after! 2500 #(reset! toast nil)))
        persist-transcript!
        ;; Persist the current transcript to the session stores (the checkpoint
        ;; wins on resume, so /undo and /redo stick across restarts).
        (fn []
          (when (and stores @thread-ref)
            (try (session/persist! stores @thread-ref (:messages @state))
                 (catch Exception _ nil))))
        save-settings!
        ;; Persist the edited leaf to the layer that should own it (project file
        ;; when in a real project, else the user file), so /settings, /models and
        ;; /think changes take effect under project > user precedence without
        ;; baking the rest of the resolved map into that file. Then re-resolve the
        ;; live chat cfg (CLI/opts overrides still win in resolve-chat).
        (fn [m p]
          (reset! settings-cfg m)
          (try
            (let [{:keys [path project?]} (settings/edit-target-file opts)]
              (settings/write-leaf! path p (get-in m p))
              (flash! (if project? "saved settings (project)" "saved settings")))
            (catch Exception e
              (flash! (str "save failed: " (.getMessage e)))))
          (reset! cfg (config/resolve-chat m opts)))
        apply-settings!
        ;; Persist a new settings map and refresh the dialog's rows in place.
        (fn [m p]
          (save-settings! m p)
          (swap! dialog assoc :items (settings/rows-for-map m)))
        commit-edit!
        ;; Enter in the value editor: apply the typed string at the row's path,
        ;; coercing by its type, then return to the list.
        (fn []
          (when-let [ed (:editor @dialog)]
            (apply-settings! (settings/set-value-at-path @settings-cfg
                                                          (:path ed)
                                                          (:value ed)
                                                          (:type ed))
                             (:path ed))
            (swap! dialog dissoc :editor)))
        cancel-edit!
        ;; Esc in the value editor: drop the draft, back to the list.
        (fn [] (swap! dialog dissoc :editor))
        board-tasks
        ;; The current project's tasks, or nil when the board is not ready.
        (fn [] (when board-ready?
                 (try (h/task-list @harness (:id board)) (catch Exception _ nil))))
        model-rows
        ;; /models: one row per provider alias, the active chat provider checked.
        (fn []
          (let [providers (:providers @settings-cfg)
                active (or (get-in @settings-cfg [:chat :provider]) :deepseek)]
            (mapv (fn [[alias p]]
                    {:label (str (if (= alias active) "✓ " "  ") (name alias))
                     :value (str (or (:model p) "") " @ " (or (:base-url p) ""))
                     :id alias})
                  (sort-by key providers))))
        effort-rows
        ;; /think: the reasoning rungs the llm effort knob accepts (low/high/max).
        (fn []
          (let [active (or (:reasoning @cfg) "high")]
            (mapv (fn [r]
                    {:label (str (if (= r active) "✓ " "  ") r)
                     :value (case r
                              "low" "quick, fewer tokens"
                              "high" "provider default"
                              "max" "deepest reasoning")
                     :id r})
                  ["low" "high" "max"])))
        compact!
        ;; /compact: ask the summarizer for a standing summary of the prefix, keep
        ;; the last few exchanges verbatim, and replace the transcript. Runs on a
        ;; worker thread so the TUI stays live; the :summarize seam makes it
        ;; testable without a network.
        (fn []
          (let [msgs (:messages @state)
                n (count msgs)]
            (cond
              (:busy? @state)
              (flash! "a turn is running — compact when it finishes")

              (< n 6)
              (flash! "nothing to compact yet")

              :else
              (let [keep (vec (take-last 4 msgs))
                    prefix (vec (drop-last 4 msgs))
                    transcript
                    (str/join
                     "\n"
                     (map (fn [e]
                            (str (name (:role e)) ": "
                                 (str/trim (str (or (:text e) (:content e) (:name e) "")))))
                          prefix))
                    summarize (or (:summarize opts)
                                  #(summarize-transcript strong % usage))]
                (swap! state assoc :busy? true)
                (future
                  (try
                    (let [summary (summarize transcript)]
                      (swap! state assoc
                             :messages (into [{:role :system
                                               :text (str "Context so far: " summary)}]
                                             keep)
                             :busy? false)
                      (when (and stores @thread-ref)
                        (try
                          (session/persist! stores @thread-ref (:messages @state))
                          (catch Exception _ nil)))
                      (ui/on-gui
                       (fn []
                         (flash! (str "compacted " n " → " (inc (count keep)) " messages")))))
                    (catch Exception e
                      (swap! state assoc :busy? false)
                      (ui/on-gui
                       (fn []
                         (flash! (str "compact failed: " (or (ex-message e) "?"))))))))))))
        open-dialog!
        ;; The sheet seam: a resolved :dialog command opens a modal sheet. Every
        ;; :sheet-id command now has a real sheet: /help, /settings, /config,
        ;; /history, /models, /think, /memory; /tree and /execs report their
        ;; honest empty state (no fanout, no async execs in the chat loop).
        (fn [kind & [extra]]
          (swap! picker assoc :open? false)
          (case kind
            :help (reset! dialog
                         {:kind :help
                          :items (mapv (fn [c]
                                         {:label (if (seq (:aliases c))
                                                   (str (:name c) " (" (str/join ", " (:aliases c)) ")")
                                                   (:name c))
                                          :value (:description c)
                                          :command c})
                                       (cmd/commands))
                          :selected 0 :filter "" :tab 0})
            (:settings :config) (reset! dialog
                                       {:kind kind
                                        :items (settings/rows-for-map @settings-cfg)
                                        :selected 0 :filter "" :tab 0})
            :history (reset! dialog
                             {:kind :history
                              :items (vec
                                      (map-indexed
                                       (fn [i text]
                                         (let [one (str/replace text "\n" " ")
                                               label (if (> (count one) 46)
                                                       (str (subs one 0 46) "…")
                                                       one)]
                                           {:label label
                                            :value (str (inc i) " ago")
                                            :text text}))
                                       (reverse (user-texts))))
                              :selected 0 :filter "" :tab 0})
            :models (reset! dialog
                            {:kind :models
                             :items (model-rows)
                             :selected 0 :filter "" :tab 0})
            :effort (reset! dialog
                            {:kind :effort
                             :items (effort-rows)
                             :selected 0 :filter "" :tab 0})
            :memory (reset! dialog
                            {:kind :memory
                             :items (if (seq facts)
                                      (mapv (fn [[k v]] {:label (name k) :value (str v)}) facts)
                                      [{:label "no salient facts yet"
                                        :value "facts appear when a session closes"}])
                             :selected 0 :filter "" :tab 0})
            :tree (reset! dialog
                          {:kind :tree
                           :items [{:label "single agent"
                                    :value "the chat loop does not fan out yet"}]
                           :selected 0 :filter "" :tab 0})
            :execs (reset! dialog
                           {:kind :execs
                            :items [{:label "no running execs"
                                     :value "tools run synchronously per turn"}]
                            :selected 0 :filter "" :tab 0})
            :board (reset! dialog
                           {:kind :board
                            :items (board-rows (or (:tasks extra) (board-tasks)))
                            :selected 0 :filter "" :tab 0})
            :tasks (reset! dialog
                           {:kind :tasks
                            :title (:title extra)
                            :items (mapv task-row (:tasks extra))
                            :selected 0 :filter "" :tab 0})
            :task (reset! dialog
                          {:kind :task
                           :title (:title extra)
                           :items (task-detail-rows (:task extra))
                           :selected 0 :filter "" :tab 0})))
        task-command!
        ;; /task: parse the rest and run add/list/show/start/close/set-status
        ;; against the harness taskboard. Mutations flash a one-line result;
        ;; list/show open the read-only :tasks/:task sheets. Every branch is a
        ;; thin call over the same h/ functions tools/dispatch exposes to the
        ;; model, so the shell and the agent stay on one surface.
        (fn [rest]
          (let [{:keys [sub positional flags]} (parse-task-line rest)
                pid (:id board)]
            (if-not board-ready?
              (flash! "taskboard not ready — run `faraday init` in the project first")
              (case (or sub "list")
                "add"
                (let [title (or (:title flags)
                                (when (seq positional) (str/join " " positional)))]
                  (if (str/blank? title)
                    (flash! "usage: /task add <title> [--priority high] [--scope rel/path]")
                    (try
                      (let [sid (when-let [rel (:scope flags)]
                                  (some-> (h/scope-by-rel @harness pid rel) :id))
                            t (h/task-add! @harness {:project-id pid
                                                     :scope-id sid
                                                     :title title
                                                     :body (or (:body flags) "")
                                                     :priority (or (:priority flags) "normal")
                                                     :epic-id (:epic flags)
                                                     :assignee (:assignee flags)
                                                     :dedup-key (:dedup-key flags)})]
                        (flash! (str "added " (:id t))))
                      (catch Exception e
                        (flash! (str "task add failed: " (or (ex-message e) "?")))))))

                "list"
                (let [status (:status flags)
                      ts (h/task-list @harness pid :status status)]
                  (if (seq ts)
                    (open-dialog! :tasks {:title (or status "all tasks") :tasks ts})
                    (flash! (str "no tasks" (when status (str " with status " status))))))

                "show"
                (let [id (or (first positional) (:id flags))
                      t (and id (try (h/task @harness id) (catch Exception _ nil)))]
                  (if t
                    (open-dialog! :task {:title (str "task " (:id t)) :task t})
                    (flash! (str "no task " (or id "?")))))

                "start"
                (let [id (first positional)]
                  (if id
                    (try (let [t (h/task-start @harness id)]
                           (flash! (str (:id t) " → " (:status t))))
                         (catch Exception e (flash! (str "start failed: " (or (ex-message e) "?")))))
                    (flash! "usage: /task start <id>")))

                "close"
                (let [id (first positional)]
                  (if id
                    (try (let [t (h/task-set-status! @harness id "done")]
                           (flash! (str (:id t) " → " (:status t))))
                         (catch Exception e (flash! (str "close failed: " (or (ex-message e) "?")))))
                    (flash! "usage: /task close <id>")))

                "set-status"
                (let [id (first positional)
                      status (or (:status flags) (second positional))]
                  (if (and id status)
                    (try (let [t (h/task-set-status! @harness id status)]
                           (flash! (str (:id t) " → " (:status t))))
                         (catch Exception e (flash! (str "set-status failed: " (or (ex-message e) "?")))))
                    (flash! "usage: /task set-status <id> <status>")))

                ;; unknown verb — point at the supported surface
                (flash! "usage: /task add|list|show|start|close|set-status …")))))
        bg!
        ;; Run thunk on a worker thread; on error, flash "<prefix>: <msg>".
        (fn [prefix thunk]
          (future
            (try
              (thunk)
              (catch Exception e
                (ui/on-gui (fn [] (flash! (str prefix ": " (or (ex-message e) "?")))))))))
        loop-run-command!
        ;; /run <task-id|manifest.edn>: run a board task through the honesty
        ;; path, or run a loop manifest end to end, and flash a one-line result.
        (fn [rest]
          (let [rest (str/trim (str rest))]
            (cond
              (str/blank? rest)
              (flash! "usage: /run <task-id|manifest.edn>")

              (and board-ready?
                   (try (some? (h/task @harness rest)) (catch Exception _ nil)))
              (bg! "run failed"
                   (fn []
                     (let [r (run/run-task! proj-root rest)]
                       (ui/on-gui
                        (fn []
                          (flash! (str "task " (name (or (:agent-status r) :done))
                                   (if (:green? r) " ✓" " ✗"))))))))

              :else
              (let [f (java.io.File. rest)
                    manifest (when (.isFile f)
                               (try (edn/read-string (slurp f)) (catch Exception _ nil)))]
                (if-not (map? manifest)
                  (flash! (str "no task or manifest " rest))
                  (bg! "run failed"
                       (fn []
                         (let [r (run/run-manifest! (or proj-root cwd) manifest :task (:task manifest))]
                           (ui/on-gui
                            (fn []
                              (flash! (str "loop " (name (get-in r [:outcomes 0 :outcome :status] :done))))))))))))))
        spawn-command!
        ;; /spawn <manifest.edn> [N]: fan out N independent agents of one loop
        ;; manifest over the scheduler, then flash how many reached :done.
        (fn [rest]
          (let [tokens (str/split (str/trim (str rest)) #"\s+")
                path (first tokens)
                n (try (Long/parseLong (str (second tokens))) (catch Exception _ 1))
                f (when (seq path) (java.io.File. path))
                manifest (when (and f (.isFile f))
                           (try (edn/read-string (slurp f)) (catch Exception _ nil)))]
            (cond
              (not (map? manifest))
              (flash! "usage: /spawn <manifest.edn> [N]")

              (or (< n 1) (> n 64))
              (flash! "N out of range (1..64)")

              :else
              (bg! "spawn failed"
                   (fn []
                     (let [r (run/run-manifest! (or proj-root cwd) manifest :task (:task manifest) :n n)
                           done (count (filter #(= :done (get-in % [:outcome :status])) (:outcomes r)))]
                       (ui/on-gui (fn [] (flash! (str "spawned " done "/" n " done"))))))))))
        run-command!
        ;; The shell's action seam: a resolved command id becomes behaviour here,
        ;; exactly as nodecode maps a command name to an action. clear/quit are
        ;; wired first; :dialog commands open a sheet via open-dialog!.
        (fn [resolved]
          (let [c (:command resolved)]
            (if (some? (:sheet-id c))
              (open-dialog! (:sheet-id c))
              (case (:action-id c)
                :clear (do (swap! state assoc :messages [] :error nil)
                           (when (and stores @thread-ref)
                             (try (session/persist! stores @thread-ref [])
                                  (catch Exception _ nil)))
                           (flash! "session cleared"))
                :quit (tui/quit!)
                :status (flash! (str (count (:messages @state)) " messages · "
                                     (if (:busy? @state) "working" "idle")
                                     " · model " (or (:model @cfg) "faraday")))
                :init (let [p (settings/scaffold! settings-path)]
                        (flash! (str "settings at " p)))
                :privacy (flash! "prompts go to the configured model provider; tools run locally")
                :cost (let [u @usage
                            p (long (or (:prompt_tokens u) 0))
                            c (long (or (:completion_tokens u) 0))
                            n (long (or (:requests u) 0))]
                        (flash! (str "↑" p " ↓" c " · " n " calls")))
                :compact (compact!)
                :export (let [target (try
                                       (export-session! (:messages @state) @thread-ref
                                                        (str/trim (str (:rest resolved)))
                                                        {:cwd cwd})
                                       (catch Exception e
                                         (flash! (str "export failed: " (or (ex-message e) "?")))
                                         nil))]
                          (when target
                            (flash! (str "saved " target))))
                :task (task-command! (:rest resolved))
                :undo (if-let [h (history-undo @state)]
                        (do (swap! state assoc :messages (:messages h) :redo (:redo h))
                            (persist-transcript!)
                            (flash! (str "rewound " (count (peek (:redo h))) " entries")))
                        (flash! "nothing to undo"))
                :redo (if-let [h (history-redo @state)]
                        (let [n (count (peek (:redo @state)))]
                          (swap! state assoc :messages (:messages h) :redo (:redo h))
                          (persist-transcript!)
                          (flash! (str "returned " n " entries")))
                        (flash! "nothing to redo"))
                :fork (if (:busy? @state)
                        (flash! "a turn is running — fork after it")
                        (let [new-id (session/default-thread-id)]
                          (when (and stores @thread-ref)
                            (try (session/persist! stores @thread-ref (:messages @state))
                                 (catch Exception _ nil)))
                          (reset! thread-ref new-id)
                          (when (and stores new-id)
                            (try (session/persist! stores new-id (:messages @state))
                                 (catch Exception _ nil)))
                          (flash! (str "forked " new-id))))
                :run (loop-run-command! (:rest resolved))
                :spawn (spawn-command! (:rest resolved))
                (flash! (str (:name c) " — not wired yet"))))))
        close-dialog!
        (fn [] (reset! dialog nil))
        selected-dialog-item!
        ;; The item under the dialog's cursor, in the filtered list the view
        ;; renders, so :activate and :toggle act on exactly what is on screen.
        (fn []
          (let [items (dialog/filter-items (:items @dialog) (:filter @dialog))]
            (nth items (:selected @dialog) nil)))
        toggle-settings-row!
        ;; Space on a /settings toggle row flips it by :path (the filtered index
        ;; does not match the source map, so index-based toggle would hit the
        ;; wrong row), then persists — the same path edit enter performs.
        (fn []
          (when-let [it (selected-dialog-item!)]
            (when (= :toggle (:kind it))
              (apply-settings! (settings/toggle-at-path @settings-cfg (:path it))
                               (:path it)))))
        dialog-activate!
        ;; Enter on a row runs it. /help rows carry the full command, so
        ;; activating one re-runs the command exactly as if it had been typed.
        ;; /settings toggles booleans in place and opens the value editor for
        ;; strings/numbers; /config, /memory, /tree and /execs are read-only.
        ;; /history picks the row's text into the composer; /models and /think
        ;; (effort) select a provider or reasoning rung and persist it.
        (fn []
          (when-let [it (selected-dialog-item!)]
            (case (:kind @dialog)
              :settings
              (cond
                (= :toggle (:kind it))
                (toggle-settings-row!)

                (= :value (:kind it))
                (swap! dialog assoc :editor
                       {:path (:path it) :label (:label it) :desc (:desc it)
                        :type (:type it)
                        :value (if (:masked? it)
                                 ""
                                 (str (get-in @settings-cfg (:path it))))})

                :else nil)

              :history
              (do (close-dialog!)
                  (reset! draft (:text it))
                  (reset! hist-idx nil))

              :models
              (do (save-settings! (assoc-in @settings-cfg [:chat :provider] (:id it))
                                  [:chat :provider])
                  (swap! dialog assoc :items (model-rows))
                  (flash! (str "chat provider → " (name (:id it)))))

              :effort
              (do (save-settings! (assoc-in @settings-cfg [:chat :reasoning] (:id it))
                                  [:chat :reasoning])
                  (swap! dialog assoc :items (effort-rows))
                  (flash! (str "reasoning → " (:id it))))

              (:config :memory :tree :execs) nil
              ;; default: help and any sheet whose rows carry a :command
              (do (close-dialog!)
                  (when-let [c (:command it)]
                    (run-command! {:command c}))))))
        dialog-key!
        ;; The dialog's :on-key, bubbled from its :focus-sink. Mapped keys move
        ;; the cursor / toggle / fold / filter; enter activates; everything else
        ;; declines (esc then falls through to the overlay's :on-close).
        (fn [ev]
          (when @dialog
            (if (:editor @dialog)
              ;; value editor: the entry owns typing; enter commits (entry
              ;; :on-activate), esc cancels (editor overlay :on-close). Decline
              ;; everything else so it falls through to the overlay.
              false
              (if-let [action (dialog/key-action ev (seq (:tabs @dialog)))]
                (do (cond
                      (= [:toggle] action)
                      (if (= :settings (:kind @dialog))
                        (toggle-settings-row!)
                        (swap! dialog #(dialog/apply-action % action)))

                      (= :activate (first action))
                      (dialog-activate!)

                      :else
                      (swap! dialog #(dialog/apply-action % action)))
                    true)
                false))))
        clear-draft!
        ;; A typed-alone command consumes itself: the composer empties, recall
        ;; resets, and the picker closes, so the next keystroke starts a fresh
        ;; line — the same lifecycle nodecode gives an executed command.
        (fn []
          (reset! draft "")
          (reset! hist-idx nil)
          (swap! picker assoc :open? false))
        complete!
        (fn []
          (let [matches (cmd/match-commands (cmd/filter-text (str @draft)))
                sel (min (:selected @picker) (max 0 (dec (count matches))))]
            (when (and (seq matches) (>= sel 0))
              (let [c (nth matches sel)]
                (reset! draft (str (:matched c) " "))
                (reset! hist-idx nil)
                (swap! picker assoc :open? false)))))
        send!
        (fn []
          (let [text (str/trim (str @draft))]
            (cond
              (empty? text) nil

              (:busy? @state)
              ;; sending while the agent is working interrupts the running turn
              ;; and stages the message; the draft stays in the composer so it
              ;; can be edited or cleared until the turn stops and it lands
              (do (interrupt!)
                  (reset! pending? true))

              :else
              (do (reset! draft "")
                  (reset! pending? false)
                  (reset! follow? true)
                  (reset! hist-idx nil)
                  (scroll-to-bottom!)
                  ((:send! sess) text)))))]
    (fn []
      (let [{:keys [messages thinking busy? error]} @state
            wrap (wrap-width opts (or @cols (live-cols) 92))
            ccfg @cfg
            _ (when-not (and (identical? messages (:msgs @ctx-cache))
                             (identical? ccfg (:cfg @ctx-cache)))
                (reset! ctx-cache {:msgs messages :cfg ccfg
                                   :readout (ctx/context-estimate ccfg (build-messages system messages))}))
            ctx-est (:readout @ctx-cache)]
        [:vbox {:spacing 0 :padding [0 1 0 1]
                :bg (themes/color :canvas)
                :on-key (fn [ev]
                          (cond
                            (and (:open? @picker) (k/match? ev "up"))
                            (do (swap! picker #(cmd/picker-step (str @draft) % :up)) true)

                            (and (:open? @picker) (k/match? ev "down"))
                            (do (swap! picker #(cmd/picker-step (str @draft) % :down)) true)

                            (and (:open? @picker) (k/match? ev "tab"))
                            (do (complete!) true)

                            (and (:open? @picker) (= :escape (:type ev)))
                            (do (swap! picker assoc :open? false) true)

                            (k/match? ev "enter")
                            (let [draft-text (str/trim (str @draft))]
                              (if (:open? @picker)
                                (do (let [matches (cmd/match-commands (cmd/filter-text draft-text))
                                          sel (min (:selected @picker) (max 0 (dec (count matches))))]
                                      (when (and (seq matches) (>= sel 0))
                                        (when-let [resolved (cmd/resolve-command (:matched (nth matches sel)))]
                                          (run-command! resolved))))
                                    (clear-draft!)
                                    true)
                                (if-let [resolved (cmd/resolve-command draft-text)]
                                  (do (run-command! resolved) (clear-draft!) true)
                                  false)))

                            (k/match? ev "ctrl+x") (do (copy-last-answer!) true)
                            (k/match? ev "ctrl+p") (do (recall! :prev) true)
                            (k/match? ev "ctrl+n") (do (recall! :next) true)
                            (or (k/match? ev "up") (k/match? ev "down")
                                (k/match? ev "pgup") (k/match? ev "pgdn")
                                (k/match? ev "home") (k/match? ev "end"))
                            (do (transcript-scroll! ev follow?) true)))}
         [:scroll {:orientation :vertical :vexpand true}
          (transcript-view-cached messages thinking expanded wrap)]
         [:hbox {:spacing 2 :margin [1 0 0 0]}
          (if busy?
            [:spinner {:tick @tick :label "working" :color (themes/color :tool)}]
            [:label {:label "● ready" :color (themes/color :assistant) :bold true}])
          [:label {:label (str "model " (or (:model @cfg) "faraday")) :color (themes/color :muted) :dim true}]
          (let [recall-hint (when (some? @hist-idx)
                              (str "recall " (inc @hist-idx) "/" (count (user-texts))
                                   " · ctrl+n newer · ctrl+p older"))
                pending-hint (when @pending?
                               "staged — edit or clear to cancel")
                follow-hint (when-not @follow?
                              "scrolled up — Home top · End/pgdn re-follow")]
            [:label {:label (or @toast @copied recall-hint pending-hint follow-hint
                                "ctrl+c quit · / command · ctrl+x copy · ctrl+p/n recall · ↑/↓ scroll")
                     :color (if (or @toast @copied recall-hint pending-hint follow-hint) (themes/color :assistant) (themes/color :muted))
                     :dim (not (or @toast @copied recall-hint pending-hint follow-hint))
                     :halign :end :hexpand true}])]
         (when error
           [:label {:label (str "⚠ " error) :color (themes/color :danger)}])
         (when (:open? @picker)
           (command-dropdown (cmd/match-commands (cmd/filter-text (str @draft)))
                             (:selected @picker)))
         (when @dialog
           (if-let [ed (:editor @dialog)]
             (dialog/editor-view
              {:label (or (:desc ed) (:label ed))
               :value (:value ed)
               :on-change (fn [t] (swap! dialog assoc-in [:editor :value] t))
               :on-activate commit-edit!
               :on-close cancel-edit!})
             (dialog/dialog-view
              {:title (or (:title @dialog) (name (:kind @dialog)))
               :items (:items @dialog)
               :selected (:selected @dialog)
               :filter (:filter @dialog)
               :filter? true
               :footer (case (:kind @dialog)
                         :settings "↑↓ move · type filter · enter toggle/edit · esc close"
                         :config "read-only · ↑↓ move · type filter · esc close"
                         "↑↓ move · type filter · enter run · esc close")
               :max-rows 12 :width 64
               :on-close close-dialog!
               :on-key dialog-key!})))
         [:hbox {:bg (themes/color :composer) :hexpand true :spacing 1
                 :margin [1 0 1 0] :padding [0 1]}
          [:label {:label "›" :color (themes/color :accent) :bold true}]
          ;; The entry is always focused (:autofocus) and a focused glimmer-tui
          ;; entry paints reverse video, so pre-invert its colours: reverse then
          ;; resolves back to light-on-composer instead of a white input bar.
          [:entry {:text @draft
                   :placeholder (if busy? "Type to steer" "Ready")
                   :hexpand true :autofocus true
                   :color (themes/color :composer) :bg (themes/color :body)
                   :on-change (fn [t]
                                (reset! draft t)
                                (if (cmd/completing? t)
                                  (swap! picker #(cmd/picker-step t % :open))
                                  (swap! picker assoc :open? false)))
                   :keys (when-not @follow?
                           {:cursor-start ["ctrl+a"] :cursor-end ["ctrl+e"]})
                   :on-activate send!}]
          [:label {:label (if busy? "●" "○")
                   :color (if busy? (themes/color :tool) (themes/color :muted))
                   :bold true}]]
         [:hbox {:bg (themes/color :chrome-bar) :hexpand true :spacing 2 :padding [0 1]}
          [:label {:label (footer-readout {:branch branch
                                           :thread-id @thread-ref
                                           :cwd cwd
                                           :usage @usage
                                           :ctx ctx-est
                                           :model (:model ccfg)})
                   :color (themes/color :chrome) :hexpand true}]]]))))

(defn- chat-flags
  "--key v | --key=v -> {key v} for chat's small flag set."
  [args]
  (loop [as (seq args) m {}]
    (if-not as
      m
      (let [a (first as)]
        (if (str/starts-with? a "--")
          (let [[k v] (str/split (subs a 2) #"=" 2)]
            (if v
              (recur (next as) (assoc m (keyword k) v))
              (recur (nnext as) (assoc m (keyword k) (second as)))))
          (recur (next as) m))))))

(defn- default-mounts
  "Host dirs the operator's config lives in, mounted read-only at the sandbox
  user's matching guest home paths (/home/sandbox is writable in this sandbox),
  so faraday (or a tool) inside the exec can READ the same ~/.faraday/
  settings.json and ~/.nodecode/auth.json as the host without writing them."
  []
  (let [home (System/getProperty "user.home")]
    (vec
     (for [[h g] [[(str home "/.faraday") "/home/sandbox/.faraday"]
                  [(str home "/.nodecode") "/home/sandbox/.nodecode"]]
           :when (.isDirectory (java.io.File. h))]
       {:host h :guest g :readonly? true}))))

(defn- mount-flags
  "Parse repeatable --mount host:guest[:ro|rw] (or --mount=...) into a vector
  of {:host :guest :readonly?}; explicit flags override defaults/settings."
  [args]
  (loop [as (seq args) acc []]
    (if-not as
      acc
      (let [a (first as)]
        (cond
          (str/starts-with? a "--mount=")
          (let [spec (config/parse-mount (subs a (count "--mount=")))]
            (recur (next as) (cond-> acc spec (conj spec))))

          (= a "--mount")
          (let [spec (some-> (second as) config/parse-mount)]
            (recur (nnext as) (cond-> acc spec (conj spec))))

          :else
          (recur (next as) acc))))))

(defn- merge-mounts
  "Merge mount-spec collections by host path; later collections win."
  [& colls]
  (vec (vals (reduce (fn [m coll]
                       (reduce (fn [mm spec] (assoc mm (:host spec) spec)) m coll))
                     {}
                     colls))))

(defn setup-chat
  "Build the chat opts + cleanup handles from flags. Returns {:opts :thread-id
  :close}. faraday.harness.console embeds these same opts in the chat mode, so
  this is the single place the chat's sandbox/tools/session are wired."
  [args]
  (let [flags (chat-flags args)
        exec-mode (or (:exec flags) "sandbox")
        _ (when (= "sandbox" exec-mode) (sandbox-preflight!))
        full-cfg (config/load-config {})
        sandbox-cfg (config/resolve-sandbox full-cfg)
        mounts (merge-mounts (default-mounts)
                             (config/resolve-mounts full-cfg)
                             (mount-flags args))
        root (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))
        proj-root (tools/find-root root)
        stores (delay (try (session/open root) (catch Exception _ nil)))
        thread-id (if-let [given (:thread flags)]
                    (do
                      (when-not @stores
                        (throw (ex-info "could not open the session store to resume a thread" {})))
                      (or (session/resolve-thread @stores given)
                          (throw (ex-info (str "no session matches thread id " (pr-str given)
                                               "; pass the full id (printed on exit) or its trailing short form")
                                          {}))))
                    (session/default-thread-id))
        sandbox (when (= "sandbox" exec-mode)
                  (sandbox-exec-factory {:workspace (System/getProperty "user.dir")
                                         :mounts mounts
                                         :packages (:apk-packages sandbox-cfg)
                                         :nix-flake (:nix-flake sandbox-cfg)
                                         :egress (:egress sandbox-cfg)}))
        _ (when sandbox
            (binding [*out* *err*] (println "warming sandbox VM (first boot)…"))
            (when-let [err ((:warm! sandbox))]
              (binding [*out* *err*]
                (println "⚠ faraday: could not warm the sandbox VM — " err)
                (println "   retry, or run with --exec shell to use host exec."))
              (System/exit 1)))
        sandbox-ctx (when sandbox
                      (str "Tools run in a jolt-sandbox microVM (alpine); the workspace is mounted at its own host path, so host paths work inside"
                           (when (:nix-flake sandbox-cfg)
                             (str "; the nix devShell " (:nix-flake sandbox-cfg) " is layered in (PATH + env vars)"))
                           (when (seq mounts)
                             (str "; mounts: "
                                  (str/join ", " (map (fn [m] (str (:host m) " -> " (:guest m))) mounts))))))
        shell-exec (if sandbox (:exec sandbox) host-exec)
        hd (delay (try (tools/open proj-root) (catch Exception _ nil)))
        board (when (and (.isFile (java.io.File. (str proj-root "/.faraday/harness.db")))
                         @hd)
                (try (h/project-by-root @hd proj-root) (catch Exception _ nil)))
        board-ready? (boolean board)
        active-tasks (when board
                       (try
                         (let [ts (h/task-list @hd (:id board))
                               active (filter #(contains? #{"open" "in_progress" "blocked"} (:status %)) ts)]
                           (not-empty
                            (mapv (fn [t]
                                    (let [title (str (:title t))]
                                      (str (:id t) " [" (:priority t) "][" (:status t) "] "
                                           (subs title 0 (min 80 (count title))))))
                                  (take 8 active))))
                         (catch Exception _ nil)))
        boot-context (boot-context-lines
                      {:cwd root
                       :proj-root proj-root
                       :board-ready? board-ready?
                       :project-desc (or (readme-desc root) (top-level-summary root))
                       :git-summary (git-summary root)
                       :recent-commits (recent-commits root)
                       :tasks active-tasks})
        pool-ref (atom nil)
        exec (fn [name args]
               (if (or (contains? #{"run_shell" "shell" "exec"} name)
                       (tools/run-code-tool? name))
                 (shell-exec name args)
                 (tools/dispatch @hd proj-root @pool-ref name args)))
        opts (cond-> {:exec exec :session @stores :thread-id thread-id
                      :cwd root :proj-root proj-root :harness hd :board board
                      :boot-context boot-context}
               sandbox-ctx (assoc :sandbox-context sandbox-ctx)
               (:model flags) (assoc :model (:model flags))
               (:base-url flags) (assoc :base-url (:base-url flags))
               (:system flags) (assoc :system (:system flags))
               (:wrap flags) (assoc :wrap (Long/parseLong (:wrap flags)))
               (:max-turns flags) (assoc :max-turns (Long/parseLong (:max-turns flags))))
        cfg (config/resolve-chat (config/load-config opts) opts)]
    (when-not (:api-key cfg)
      (throw (ex-info (str "no LLM API key for chat; set FARADAY_LLM_API_KEY or "
                           "DEEPSEEK_API_KEY, or seed ~/.faraday/settings.json")
                      {})))
    {:opts opts
     :thread-id thread-id
     :pool-ref pool-ref
     :close (fn []
              (when sandbox ((:close sandbox)))
              (when @stores
                (try (session/close! @stores thread-id) (catch Exception _ nil))))}))

(defn cmd-chat
  "Run the operator console, chat mode first (delegates to
  faraday.harness.console/run!). Flags as before: --model, --base-url, --system,
  --wrap, --max-turns, --exec sandbox|shell, --thread ID, --mount host:guest[:ro|rw]."
  [args]
  (let [{:keys [opts close thread-id pool-ref]} (setup-chat args)]
    (if-not (tui/usable-terminal?)
      (println "faraday chat needs a real terminal (stdin/stdout are not a tty).")
      (do
        ((requiring-resolve 'faraday.harness.console/run!)
         {:chat-opts opts :pool-ref pool-ref})
        (close)
        (let [resume-args (loop [as (seq args) acc []]
                            (if-not as
                              acc
                              (let [a (first as)]
                                (cond
                                  (str/starts-with? a "--thread=") (recur (next as) acc)
                                  (= a "--thread") (recur (nnext as) acc)
                                  :else (recur (next as) (conj acc a))))))]
          (println "bye — resume with:")
          (println (str "  faraday chat"
                        (when (seq resume-args) (str " " (str/join " " resume-args)))
                        " --thread " (session/short-thread-id thread-id))))
        (shutdown-agents)))))
