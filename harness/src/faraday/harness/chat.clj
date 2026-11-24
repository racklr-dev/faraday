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
;; Pure, tested pieces: word-wrap, build-messages, format-tool-result, parse-action.
(ns faraday.harness.chat
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [faraday.harness :as h]
            [faraday.harness.config :as config]
            [faraday.harness.llm :as llm]
            [faraday.harness.session :as session]
            [faraday.harness.tools :as tools]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core :as tui]
            [glimmer-tui.keys :as k]
            [glimmer-tui.widget :as w]
            [glimmer-tui.widgets.scroll :as scroll]
            [jolt.host]
            [jolt.markdown.core :as md]))

;; -- the design language ------------------------------------------------------
;; faraday's terminal surfaces borrow nodecode's Tungstene palette and chrome:
;; dark canvas, cool section bars, blue user / green assistant / amber tool
;; hues. glimmer-tui folds each hex down to the terminal's palette, so the same
;; props stay legible on a 256-colour and a 16-colour tty.
(def palette
  {:canvas "#1c1f26" :section "#181b22" :surface "#232730" :composer "#3d414a"
   :selection "#3a3f4c" :hover "#292d37" :chip "#575b64" :slab "#1b2125"
   :foreground "#ebeef4" :muted "#8e96a4" :chrome "#d2d6de"
   :user "#84b2ff" :assistant "#82d6b0" :tool "#e4b860" :heading "#bcdcf5"
   :code "#7fb0d7" :accent "#f6c075" :danger "#ff7e7e"})

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
       "- Treat the user message as the task. If it is terse or ambiguous, make the smallest reasonable assumption and start; ask a question only when the choice changes the work itself.\n"
       "- Read before you change. Prefer one targeted command over several probes.\n"
       "- In run_shell the project directory is mounted at its own host path, so the paths in Context work verbatim. Board/scope/hook tools only work when Context says the taskboard is ready.\n"
       "- Never repeat a failed command unchanged — adapt it.\n"
       "- Your turn budget is small: stop and answer as soon as you have the result.\n"
       "\n"
       "Answer with {:done \"...\"} (or plain text for a short answer). Report concretely: files changed or commands run, and what you verified. No preamble, no filler."))

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

(def ^:dynamic *interrupt*
  "Bound by chat-session around run-turn! on the worker thread; tool executors
  poll it to stop a running command mid-flight." nil)

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
           :stderr ""})))))

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
                                                  :nix-flake nix-flake})]
                             (swap! state assoc :pool p)
                             p)))]
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
                   (try
                     (let [p (ensure-pool!)
                           r (sb-pool-exec p (argv->cmd argv) :timeout-ms 120000 :cwd workspace :interrupt *interrupt*)]
                       (if (:interrupted? r)
                         {:status :error :error "interrupted" :interrupted? true}
                         {:status (if (zero? (:exit r)) :ok :error)
                          :exit (:exit r)
                          :stdout (:stdout r)
                          :stderr (:stderr r)}))
                     (catch Exception e
                       (let [msg (str "sandbox exec failed (" (or (ex-message e) "?") ")")
                             boot? (nil? (:pool @state))]
                         (when boot?
                           (swap! state assoc :failed? true :boot-error msg))
                         {:status :error :error msg :fatal? boot?})))))))
     :close (fn []
              (when-let [p (:pool @state)]
                (try (sb-close-pool! p) (catch Exception _ nil))))}))

(defn- repair-note []
  "Return a valid action: exactly one of a tool call or {:done \"...\"}.")

(defn- model-step
  "The chat's model step: native function calling (tools -> tool_calls) with a
  repair + escalate fallback. `primary` and `strong` are provider cfgs; returns
  a canonical action map ({:tool-call ..} {:tool-calls [..]} {:done ..}) or a
  plain-text string (never nil). On a null result it retries with repair
  guidance, then escalates to the strong provider."
  [primary strong messages]
  (let [try-cfg (fn [cfg extra]
                  (let [msgs (if extra (conj messages {:role "user" :content extra}) messages)]
                    (try (llm/completion->action (assoc cfg :tools (tools/openai-tools)) msgs)
                         (catch Exception _ nil))))]
    (or (try-cfg primary nil)
        (try-cfg primary (repair-note))
        (try-cfg strong (str (repair-note) " Escalate: reason carefully."))
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

(defn run-turn!
  "Drive one agentic turn: loop model call -> tool exec until the model answers.
  opts: :call (fn messages -> content), :exec (fn name args -> result), :system,
  :max-turns, :max-stall (consecutive no-progress tool steps before stopping).
  Appends entries to `state` and clears :busy?; on a fault or stall sets :error.
  Safe to run on a worker thread (only ratom swaps, no widget touches)."
  [opts state entries]
  (let [{:keys [call exec system max-turns max-stall]} opts
        interrupt (or *interrupt* (:interrupt opts) (atom false))
        max-stall (or max-stall 3)
        run-one (fn [name args]
                  (let [res (exec name args)
                        progress? (if (contains? res :progress?)
                                    (:progress? res)
                                    (= :ok (:status res)))]
                    [{:role :tool-call :name name :args args}
                     {:role :tool-result :name name
                      :status (:status res)
                      :exit (:exit res)
                      :text (or (:stdout res) (:stderr res) "")
                      :error (:error res)
                      :fatal? (true? (:fatal? res))
                      :interrupted? (true? (:interrupted? res))
                      :progress? progress?}]))
        step! (fn [es]
                (let [resp (call (build-messages system es))
                      action (if (string? resp) (parse-action resp) resp)
                      tcs (cond
                            (:tool-call action) [(:tool-call action)]
                            (:tool-calls action) (:tool-calls action)
                            :else nil)]
                  (cond
                    ;; one or several tool calls (native tool_calls, EDN, or
                    ;; DSML): execute each, feed results back, loop
                    (seq tcs)
                    (let [entries (vec (mapcat (fn [tc] (run-one (:name tc) (:args tc))) tcs))
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
    (loop [left (or max-turns 12)
           stall 0
           es entries]
      (cond
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
        (let [outcome (try (step! es)
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
  "The live root widget atom, or nil. Reached through glimmer's private live-root
  because no public getter exists; this is only for pinning the transcript scroll
  to the bottom and fails closed."
  []
  (letfn [(inst-widget [inst-atom]
            (when inst-atom
              (let [inst @inst-atom]
                (or (:widget inst)
                    (some-> (first (:children inst)) inst-widget)))))]
    (when-let [state @#'glimmer.core/live-root]
      (inst-widget (:inst state)))))

(defn- find-scroll-widget
  [w]
  (when w
    (if (= :scroll (:tag @w))
      w
      (some find-scroll-widget (:children @w)))))

(defn- scroll-to-bottom!
  "Pin the transcript scroll to the bottom on the loop thread. Setting :y-offset
  to a huge number is enough: arrange-scroll clamps it to the fresh max offset
  at layout time, so this never needs the current layout."
  []
  (ui/on-gui
   (fn []
     (when-let [sw (some-> (root-widget) find-scroll-widget)]
       (swap! sw update :state assoc :x-offset 0 :y-offset 2147483647)
       (w/touch!)))))

(defn- transcript-scroll!
  "Scroll the transcript viewport by what `ev` implies. The composer entry is
  always focused, so arrow/page keys never reach the :scroll widget (a sibling);
  they bubble here instead and we drive the laid-out scroll node directly."
  [ev]
  (let [{:keys [tree root]} @#'glimmer-tui.core/app]
    (when (and tree root)
      (when-let [n (some (fn [x] (when (= :scroll (w/container-kind (:tag x))) x))
                         (w/walk tree))]
        (when-let [wid (w/find-widget root (:id n))]
          (let [page (max 1 (dec (max 1 (:h (:viewport n) 0))))
                dy (cond
                     (k/match? ev "up") -1
                     (k/match? ev "down") 1
                     (k/match? ev "pgup") (- page)
                     (k/match? ev "pgdn") page
                     :else nil)]
            (when (some? dy)
              (scroll/scroll-by! wid n 0 dy))))))))

(defn- wrap-width
  "Columns to wrap transcript text to: the terminal width (guarded) minus the
  room the scrollbar and user prefix take; falls back to 92."
  [opts]
  (let [cols (or (:wrap opts)
                 (try (let [[_ c] (jolt.host/term-size)] c)
                      (catch Throwable _ nil))
                 92)]
    (max 24 (- (long cols) 8))))

(defn markdown-segments
  "Split assistant markdown into render segments via jolt-markdown. Prose
  segments carry :spans (inline strong/em/strike/code/link) plus the plain
  :text for previews and line counts; fenced code stays verbatim. Numbered
  items render as plain text, keeping their number."
  [text]
  (mapv (fn [blk]
          (case (:type blk)
            :heading {:t :line :kind :heading
                      :text (md/spans-text (:spans blk))
                      :spans (:spans blk)}
            :quote {:t :line :kind :quote
                    :text (md/spans-text (:spans blk))
                    :spans (:spans blk)}
            :bullet {:t :line :kind :bullet
                     :text (md/spans-text (:spans blk))
                     :spans (:spans blk)}
            :numbered (let [spans (into [{:text (str (:n blk) ". ") :style nil}]
                                        (:spans blk))]
                        {:t :line :kind :text
                         :text (md/spans-text spans)
                         :spans spans})
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
             (if (= :code (:t seg))
               (map (fn [l] [:code l]) (:lines seg))
               [[(:kind seg) (:text seg)]]))
           (markdown-segments text))))

(defn chat-session
  "A headless chat: owns the transcript and drives the tool loop. Returns
  {:state (ratom {:messages :busy? :error}) :send! (fn text)
  :interrupt! (fn)}. The terminal TUI renders :state; the web console can
  render and drive the same :state, so both clients see one conversation.
  interrupt! asks a running turn to stop at the next step boundary."
  [{:keys [call exec system max-turns max-stall stores thread-id]}]
  (let [state (r/atom {:messages (or (when (and stores thread-id)
                                      (try (session/resume stores thread-id)
                                           (catch Exception _ nil)))
                                    [])
                       :busy? false :error nil})
        interrupt (atom false)
        send! (fn [text]
                (let [text (str/trim (str text))]
                  (when (and (seq text) (not (:busy? @state)))
                    (let [es (conj (:messages @state) {:role :user :text text})]
                      (reset! interrupt false)
                      (swap! state assoc :busy? true :error nil :messages es)
                      (future
                        (binding [*interrupt* interrupt]
                          (run-turn! {:call call :exec exec :system system
                                      :max-turns max-turns :max-stall max-stall
                                      :interrupt interrupt}
                                     state es))
                        (when (and stores thread-id)
                          (try (session/persist! stores thread-id (:messages @state))
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
         (let [lines (md/wrap-spans (:spans seg) wrap)
               kind (:kind seg)]
           (if (= :bullet kind)
             (map-indexed
              (fn [i line]
                {:t :line :role :assistant
                 :kind (if (zero? i) :bullet :bullet-cont)
                 :text (md/spans-text line)
                 :spans line})
              lines)
             (map (fn [line] {:t :line :role :assistant :kind kind
                              :text (md/spans-text line)
                              :spans line})
                  lines)))))
     (markdown-segments (:text e)))

    :tool-call
    (map (fn [line] {:t :line :role :tool-call :kind :text :text line})
         (word-wrap (str "$" (when (seq (:args e))
                               (str " " (str/join " " (map str (:args e))))))
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
                 (and (= :line (:t seg))
                      (str/blank? (str (:text seg))))))
       vec))

(defn- line-style
  "Colour/style props for one transcript line."
  [pal role kind]
  (case role
    :user {:color (:user pal) :bold true}
    :assistant (case kind
                 :heading {:color (:heading pal) :bold true}
                 :quote {:color (:muted pal) :dim true}
                 :bullet {:color (:foreground pal)}
                 {:color (:foreground pal)})
    :tool-call {:color (:tool pal) :bold true}
    :tool-result {:color (:muted pal) :dim true}
    {:color (:foreground pal)}))

(defn- line-prefix
  [role kind]
  (case role
    :user "you> "
    :tool-call "  → "
    :tool-result "    "
    :assistant (case kind
                 :heading "▍ "
                 :quote "│ "
                 :bullet "  • "
                 :bullet-cont "    "
                 "")
    ""))

(defn- span-props
  "Style props for one inline span: the line's own style by default, overlaid
  with the span's emphasis. glimmer-tui has no italic or strikethrough, so em
  renders underlined and strikethrough renders dim (the terminal conventions)."
  [pal base {:keys [style]}]
  (let [style (or style #{})]
    (cond-> base
      (:strong style) (assoc :bold true)
      (:em style)     (assoc :underline true)
      (:strike style) (assoc :dim true)
      (:code style)   (assoc :color (:code pal))
      (:link style)   (assoc :color (:accent pal) :underline true))))

(defn- spans-hiccup
  "A prose line as hiccup: an optional prefix label (bullet/heading/quote
  marker) followed by one label per span, so inline styles render per-span."
  [pal role kind prefix spans]
  (let [base (line-style pal role kind)]
    (if (and (empty? prefix) (= 1 (count spans)))
      (let [s (first spans)]
        [:label (assoc (span-props pal base s) :label (:text s))])
      (into [:hbox {:spacing 0}]
            (concat
             (when (seq prefix)
               [[:label (assoc base :label prefix)]])
             (map (fn [s]
                    [:label (assoc (span-props pal base s) :label (:text s))])
                  spans))))))

(defn segment-hiccup
  "One render segment as hiccup."
  [pal seg]
  (if (= :code (:t seg))
    [:box {:bg (:slab pal) :hexpand true}
     (into [:vbox {:spacing 0 :padding [1 2 1 1]}]
           (map (fn [line]
                  [:label {:label line :color (:code pal)}])
                (:lines seg)))]
    (let [{:keys [role kind text spans]} seg]
      (if (and (= role :assistant) (seq spans))
        (spans-hiccup pal role kind (line-prefix role kind) spans)
        [:label (assoc (line-style pal role kind)
                       :label (str (line-prefix role kind) text))]))))

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
            :color (:muted palette)
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
    [:box {:bg (:slab palette) :hexpand true}
     (into [:vbox {:spacing 0 :padding [0 2 1 1]}]
           (map (fn [line] [:label {:label line :color (:code palette)}]) lines))]))

(defn- seg-line-count
  "Rendered line count of entry-segments output: prose segments are one line
  each, a code segment counts its verbatim lines."
  [segs]
  (reduce (fn [n seg] (+ n (if (= :code (:t seg)) (count (:lines seg)) 1))) 0 segs))

(defn- assistant-summary
  "Fold header for a long assistant answer: its line count and a one-line
  preview of its prose."
  [segs lines]
  (let [preview (some (fn [s] (when (= :line (:t s)) (str/trim (:text s)))) segs)
        preview (when (and preview (seq preview))
                  (if (> (count preview) 40) (str (subs preview 0 40) "…") preview))]
    (str "assistant · " lines " lines" (when preview (str " · " preview)))))

(defn- entry-hiccup
  "One conversation entry as hiccup: tool results, long code blocks and long
  assistant prose are all foldable (collapsed to a header; Enter/click opens).
  The latest assistant answer stays open so the operator reads it whole; only
  earlier long answers fold. `i` is the entry index — stable because messages
  only append."
  [expanded wrap last-assistant? i e]
  (case (:role e)
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
             (into [:vbox {:spacing 0}] (map (partial segment-hiccup palette) segs)))])
        (into [:vbox {:spacing 0}]
              (map-indexed
               (fn [ci seg]
                 (if (and (= :code (:t seg)) (> (count (:lines seg)) 12))
                   (let [k [i :code ci]
                         open? (contains? @expanded k)]
                     [:vbox {:spacing 0}
                      (fold-header expanded k open? (str "code · " (count (:lines seg)) " lines"))
                      (when open? (segment-hiccup palette seg))])
                   (segment-hiccup palette seg)))
               segs))))

    (into [:vbox {:spacing 0}]
          (map (partial segment-hiccup palette) (entry-segments e wrap)))))

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

(defn chat-app
  "The agentic chat component. `opts` accepts :system, :wrap, :model, :base-url,
  :api-key (the latter three override config/resolve-chat), :max-turns, and the
  two test seams :call (fn messages -> content) and :exec (fn name args ->
  result-map) that replace the real model and tool executor. Returns the
  component fn to hand to ui/run."
  [opts]
  (let [full-cfg (config/load-config opts)
        cfg (config/resolve-chat full-cfg opts)
        strong (config/resolve-provider full-cfg :deepseek-pro opts)
        wrap (wrap-width opts)
        pal palette
        breaker (atom {:failures 0 :open? false})
        call (or (:call opts)
                 (with-circuit-breaker breaker #(model-step cfg strong %)))
        exec (or (:exec opts) host-exec)
        max-turns (or (:max-turns opts) 12)
        stores (:session opts)
        thread-id (:thread-id opts)
        facts (when (and stores thread-id)
                (try (session/salient stores thread-id) (catch Exception _ nil)))
        system (system-with-context
                (or (:system opts) agentic-system)
                (cond-> []
                  (:boot-context opts) (into (:boot-context opts))
                  (:sandbox-context opts) (conj (:sandbox-context opts))
                  (:context cfg) (into (:context cfg)))
                facts)
        sess (chat-session {:call call :exec exec :system system :max-turns max-turns
                            :stores stores :thread-id thread-id})
        state (:state sess)
        interrupt! (:interrupt! sess)
        pending? (r/atom false)
        draft (r/atom "")
        hist-idx (r/atom nil)
        tick (r/atom 0)
        _ (tui/every! 100
                      (fn []
                        (swap! tick inc)
                        (when (and @pending? (not (:busy? @state)))
                          (reset! pending? false)
                          (let [text (str/trim (str @draft))]
                            (when (seq text)
                              (reset! draft "")
                              (reset! hist-idx nil)
                              (scroll-to-bottom!)
                              ((:send! sess) text))))))
        model (or (:model cfg) "faraday")
        expanded (r/atom #{})
        copied (r/atom nil)
        user-texts
        (fn [] (vec (keep (fn [e] (when (= :user (:role e)) (:text e))) (:messages @state))))
        recall!
        (fn [dir]
          (let [texts (user-texts)
                n (count texts)]
            (when (pos? n)
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
                  (reset! hist-idx nil)
                  (scroll-to-bottom!)
                  ((:send! sess) text)))))]
    (fn []
      (let [{:keys [messages busy? error]} @state
            last-assistant-idx (last (keep-indexed (fn [i e] (when (= :assistant (:role e)) i)) messages))]
        [:vbox {:spacing 0 :margin [0 1 0 1]
                :on-key (fn [ev]
                          (cond
                            (k/match? ev "ctrl+y") (do (copy-last-answer!) true)
                            (k/match? ev "ctrl+p") (do (recall! :prev) true)
                            (k/match? ev "ctrl+n") (do (recall! :next) true)
                            (or (k/match? ev "up") (k/match? ev "down")
                                (k/match? ev "pgup") (k/match? ev "pgdn"))
                            (do (transcript-scroll! ev) true)))}
         [:scroll {:orientation :vertical :vexpand true}
          (into [:vbox {:spacing 0 :padding [1 2 1 1]}]
                (if (empty? messages)
                  [[:label {:label "Type a task; faraday runs tools and answers." :dim true}]]
                  (map-indexed (fn [i e] (entry-hiccup expanded wrap (= i last-assistant-idx) i e)) messages)))]
         [:hbox {:spacing 2 :margin [1 0 0 0]}
          (if busy?
            [:spinner {:tick @tick :label "working" :color (:tool pal)}]
            [:label {:label "● ready" :color (:assistant pal) :bold true}])
          [:label {:label (str "model " model) :color (:muted pal) :dim true}]
          (let [recall-hint (when (some? @hist-idx)
                              (str "recall " (inc @hist-idx) "/" (count (user-texts))
                                   " · ctrl+n newer · ctrl+p older"))
                pending-hint (when @pending?
                               "staged — edit or clear to cancel")]
            [:label {:label (or @copied recall-hint pending-hint
                                "ctrl+c quit · ctrl+y copy · ctrl+p/n recall · ↑/↓ pgup/pgdn scroll")
                     :color (if (or @copied recall-hint pending-hint) (:assistant pal) (:muted pal))
                     :dim (not (or @copied recall-hint pending-hint))
                     :halign :end :hexpand true}])]
         (when error
           [:label {:label (str "⚠ " error) :color (:danger pal)}])
         [:hbox {:bg (:composer pal) :hexpand true :spacing 1
                 :margin [1 0 1 0] :padding [0 1]}
          [:label {:label "›" :color (:accent pal) :bold true}]
          ;; The entry is always focused (:autofocus) and a focused glimmer-tui
          ;; entry paints reverse video, so pre-invert its colours: reverse then
          ;; resolves back to light-on-composer instead of a white input bar.
          [:entry {:text @draft
                   :placeholder (if busy? "faraday is working…" "ask faraday")
                   :hexpand true :autofocus true
                   :color (:composer pal) :bg (:foreground pal)
                   :on-change #(reset! draft %)
                   :on-activate send!}]
          [:label {:label (if busy? "●" "○")
                   :color (if busy? (:tool pal) (:muted pal))
                   :bold true}]]]))))

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
        thread-id (or (:thread flags) (session/default-thread-id))
        sandbox (when (= "sandbox" exec-mode)
                  (sandbox-exec-factory {:workspace (System/getProperty "user.dir")
                                         :mounts mounts
                                         :packages (:apk-packages sandbox-cfg)
                                         :nix-flake (:nix-flake sandbox-cfg)}))
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
        (println "bye — resume with:")
        (println (str "  faraday chat"
                      (when (seq args) (str " " (str/join " " args)))
                      " --thread " thread-id))
        (shutdown-agents)))))
