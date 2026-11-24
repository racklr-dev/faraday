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
            [glimmer-tui.widget :as w]))

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

(def agentic-system
  (str "You are faraday, a direct assistant that gets things done by running tools. "
       "You have a taskboard and guardrail hooks in addition to a sandboxed shell. "
       "Available tools (call one by replying with exactly one EDN map and nothing else):\n"
       (tools/tool-prompt)
       "\n\nA tool's result comes back to you; keep calling tools until you have the answer. "
       "When you have the final answer, reply with exactly one EDN map: {:done \"your answer\"} "
       "(or plain text for a short answer)."))

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

(defn host-exec
  "The default tool executor: run argv on the host in faraday's cwd. Returns
  {:status :ok|:error :exit int|nil :stdout :stderr :error}. The :exec seam lets
  a sandbox (jolt-sandbox microVM with volume mounts) take this slot instead."
  [name args]
  (let [argv (:argv args)]
    (cond
      (not (and (vector? argv) (seq argv)))
      {:status :error :error "tool args must be {:argv [..]}"}

      (not (contains? #{"run_shell" "shell" "exec"} name))
      {:status :error :error (str "unknown tool " (pr-str name))}

      :else
      (let [r (h/run-command argv 120000)]
        (if (or (:timeout? r) (nil? (:exit r)))
          {:status :error :error "timed out" :stdout (:out r)}
          {:status (if (zero? (:exit r)) :ok :error)
           :exit (:exit r)
           :stdout (:out r)
           :stderr ""})))))

(defn- argv->cmd
  "Single-quote each argv element for the guest sh."
  [argv]
  (str/join " " (map (fn [s] (str "'" (str/replace (str s) "'" "'\\''") "'")) argv)))

(defn sandbox-exec-factory
  "Build a stateful tool executor over jolt.sandbox: a warm alpine microVM with
  the operator's cwd volume-mounted at /workspace. The pool boots lazily on the
  first tool call and is reused; (close) stops it. Returns {:exec (fn [name
  args] -> result) :close (fn [])}. Tool calls FAIL CLOSED: if the sandbox cannot
  boot, the call returns {:status :error :error ..} and every later call returns
  the same error — there is no host-exec fallback. opts: :workspace (host dir at
  /workspace), :mounts (extra host-dir->guest-dir virtiofs mounts, same shapes
  jolt.microvm/start! takes: {host guest} map, [{:host :guest :tag}] specs, or
  [[host guest] ..] pairs), :project-dir (dir whose deps.edn provides the
  sandbox stack), :cache-dir, :image (default alpine:3.20)."
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
        ensure-pool! (fn []
                       (or (:pool @state)
                           (let [p (sb-make-pool {:image image
                                                  :cache-dir cache-dir
                                                  :project-dir project-dir
                                                  :workspace workspace
                                                  :workspace-guest workspace
                                                  :mounts mounts
                                                  :writable-home? true})]
                             (swap! state assoc :pool p)
                             p)))]
    {:exec (fn [name args]
             (cond
               (not (contains? #{"run_shell" "shell" "exec"} name))
               {:status :error :error (str "unknown tool " (pr-str name))}

               (not (and (vector? (:argv args)) (seq (:argv args))))
               {:status :error :error "tool args must be {:argv [..]}"}

               (:failed? @state)
               {:status :error :error (:boot-error @state)}

               :else
               (try
                 (let [p (ensure-pool!)
                       r (sb-pool-exec p (argv->cmd (:argv args)) :timeout-ms 120000 :cwd workspace)]
                   {:status (if (zero? (:exit r)) :ok :error)
                    :exit (:exit r)
                    :stdout (:stdout r)
                    :stderr (:stderr r)})
                 (catch Exception e
                   (let [msg (str "sandbox exec failed (" (or (ex-message e) "?") ")")]
                     (when (nil? (:pool @state))
                       (swap! state assoc :failed? true :boot-error msg))
                     {:status :error :error msg})))))
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

(defn run-turn!
  "Drive one agentic turn: loop model call -> tool exec until the model answers.
  opts: :call (fn messages -> content), :exec (fn name args -> result), :system,
  :max-turns, :max-stall (consecutive no-progress tool steps before stopping).
  Appends entries to `state` and clears :busy?; on a fault or stall sets :error.
  Safe to run on a worker thread (only ratom swaps, no widget touches)."
  [opts state entries]
  (let [{:keys [call exec system max-turns max-stall]} opts
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
                    (let [entries (vec (mapcat (fn [tc] (run-one (:name tc) (:args tc))) tcs))]
                      (swap! state update :messages into entries)
                      {:cont (into es entries)
                       :progress? (boolean (some :progress? entries))})

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
    (loop [left (or max-turns 8)
           stall 0
           es entries]
      (cond
        (zero? left)
        (swap! state assoc :busy? false :error "max tool turns reached")

        (>= stall max-stall)
        (swap! state assoc :busy? false :error "no progress — stopping")

        :else
        (let [outcome (try (step! es)
                           (catch Exception e
                             {:fault (or (ex-message e) "turn failed")}))]
          (cond
            (:fault outcome) (swap! state assoc :busy? false :error (:fault outcome))
            (= :done outcome) (swap! state assoc :busy? false)
            (:cont outcome)  (recur (dec left)
                                    (if (:progress? outcome) 0 (inc stall))
                                    (:cont outcome))
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

(defn- wrap-width
  "Columns to wrap transcript text to: the terminal width (guarded) minus the
  room the scrollbar and user prefix take; falls back to 92."
  [opts]
  (let [cols (or (:wrap opts)
                 (try (let [[_ c] (jolt.host/term-size)] c)
                      (catch Throwable _ nil))
                 92)]
    (max 24 (- (long cols) 8))))

(defn- markdown-lines
  "Split assistant markdown into [[kind text] ...], kind :text/:code/:heading.
  A simple highlighter: code fences toggle :code, #-lines are :heading, ** is
  stripped (inline bold is left to a richer renderer later)."
  [text]
  (let [in-code (volatile! false)]
    (vec
     (mapcat
      (fn [line]
        (let [t (str/trim line)]
          (cond
            (str/starts-with? t "```")
            (do (vreset! in-code (not @in-code)) [])

            @in-code
            [[:code line]]

            (str/starts-with? t "#")
            [[:heading (str/trim (str/replace t #"^#+\s*" ""))]]

            :else
            [[:text (str/replace line #"\*\*(.+?)\*\*" "$1")]])))
      (str/split-lines (str text))))))

(defn chat-session
  "A headless chat: owns the transcript and drives the tool loop. Returns
  {:state (ratom {:messages :busy? :error}) :send! (fn text)}. The terminal TUI
  renders :state; the web console can render and drive the same :state, so both
  clients see one conversation."
  [{:keys [call exec system max-turns max-stall stores thread-id]}]
  (let [state (r/atom {:messages (or (when (and stores thread-id)
                                      (try (session/resume stores thread-id)
                                           (catch Exception _ nil)))
                                    [])
                       :busy? false :error nil})
        send! (fn [text]
                (let [text (str/trim (str text))]
                  (when (and (seq text) (not (:busy? @state)))
                    (let [es (conj (:messages @state) {:role :user :text text})]
                      (swap! state assoc :busy? true :error nil :messages es)
                      (future
                        (run-turn! {:call call :exec exec :system system
                                    :max-turns max-turns :max-stall max-stall}
                                   state es)
                        (when (and stores thread-id)
                          (try (session/persist! stores thread-id (:messages @state))
                               (catch Exception _ nil))))))))]
    {:state state :send! send!}))

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
        breaker (atom {:failures 0 :open? false})
        call (or (:call opts)
                 (with-circuit-breaker breaker #(model-step cfg strong %)))
        exec (or (:exec opts) host-exec)
        max-turns (or (:max-turns opts) 8)
        stores (:session opts)
        thread-id (:thread-id opts)
        facts (when (and stores thread-id)
                (try (session/salient stores thread-id) (catch Exception _ nil)))
        system (let [base (or (:system opts) agentic-system)]
                 (if (seq facts)
                   (str base "\n\nYou remember these facts from earlier sessions:\n" (pr-str facts))
                   base))
        sess (chat-session {:call call :exec exec :system system :max-turns max-turns
                            :stores stores :thread-id thread-id})
        state (:state sess)
        draft (r/atom "")
        transcript-lines
        (fn []
          (mapcat (fn [{:keys [role text name args] :as e}]
                    (case role
                      :user (map (fn [line] [role :text line]) (word-wrap text wrap))
                      :assistant (mapcat (fn [[kind line]]
                                           (map (fn [wl] [role kind wl]) (word-wrap line wrap)))
                                         (markdown-lines text))
                      :tool-call (map (fn [line] [role :text line])
                                      (word-wrap (str "$" (when (seq args)
                                                             (str " " (str/join " " (map str args))))) wrap))
                      :tool-result (map (fn [line] [role :text line])
                                        (word-wrap (format-tool-result (select-keys e [:name :status :exit :error :text])) wrap))
                      (map (fn [line] [role :text line]) (word-wrap (str text) wrap))))
                  (:messages @state)))
        send!
        (fn []
          (let [text (str/trim (str @draft))]
            (when (and (seq text) (not (:busy? @state)))
              (reset! draft "")
              (scroll-to-bottom!)
              ((:send! sess) text))))]
    (fn []
      (let [{:keys [messages busy? error]} @state
            lines (vec (transcript-lines))]
        [:vbox {:spacing 0}
         [:label {:label " faraday chat — agentic " :bold true :color :bright-cyan :margin [1 0 0 1]}]
         [:frame {:label " conversation " :border :rounded :vexpand true :margin [0 1 1 1]}
          [:scroll {:orientation :vertical :vexpand true}
           (into [:vbox {:spacing 0 :margin 0}]
                 (if (empty? lines)
                   [[:label {:label "Type a task; faraday runs tools and answers. ctrl-c quits." :dim true}]]
                   (for [[role kind line] lines]
                     [:label {:label (condp = role
                                       :user (str "you> " line)
                                       :tool-call (str "  → " line)
                                       :tool-result (str "    " line)
                                       line)
                              :color (case role
                                       :user :bright-green
                                       :assistant (case kind
                                                    :code :bright-black
                                                    :heading :bright-cyan
                                                    :default)
                                       :tool-call :bright-cyan
                                       :tool-result :bright-black)
                              :bold (or (= kind :heading) (= role :user))
                              :dim (= role :tool-result)}])))]]
         (when error
           [:label {:label (str "  " error) :color :bright-red :margin [0 1]}])
         [:hbox {:spacing 1 :margin [1 2 1 2]}
          [:entry {:text @draft
                   :placeholder (if busy? "faraday is working…" "ask faraday")
                   :hexpand true :autofocus true
                   :on-change #(reset! draft %)
                   :on-activate send!}]
          [:label {:label (if busy? "●" "○") :bold true :color :bright-cyan}]]
         [:label {:label " enter: send   ctrl-c: quit" :dim true :margin [0 2 0 2]}]]))))

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
        mounts (merge-mounts (default-mounts)
                             (config/resolve-mounts (config/load-config {}))
                             (mount-flags args))
        root (.getCanonicalPath (java.io.File. (System/getProperty "user.dir")))
        proj-root (tools/find-root root)
        stores (delay (try (session/open root) (catch Exception _ nil)))
        thread-id (or (:thread flags) (session/default-thread-id))
        sandbox (when (= "sandbox" exec-mode)
                  (sandbox-exec-factory {:workspace (System/getProperty "user.dir")
                                         :mounts mounts}))
        shell-exec (if sandbox (:exec sandbox) host-exec)
        hd (delay (try (tools/open proj-root) (catch Exception _ nil)))
        pool-ref (atom nil)
        exec (fn [name args]
               (if (contains? #{"run_shell" "shell" "exec"} name)
                 (shell-exec name args)
                 (tools/dispatch @hd proj-root @pool-ref name args)))
        opts (cond-> {:exec exec :session @stores :thread-id thread-id}
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
         {:chat-opts opts :initial-view "chat" :pool-ref pool-ref})
        (close)
        (println "bye — resume with:")
        (println (str "  faraday chat"
                      (when (seq args) (str " " (str/join " " args)))
                      " --thread " thread-id))
        (shutdown-agents)))))
