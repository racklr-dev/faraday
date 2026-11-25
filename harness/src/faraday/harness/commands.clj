;; faraday.harness.commands — the slash-command registry and its pure matcher.
;; A command is data: a name, aliases, a kind (:action runs at once, :dialog
;; opens a sheet, :input asks for one line of text), and an id the shell maps to
;; behaviour (:action-id or :sheet-id). Keeping the registry data (no closures)
;; means the matcher, resolver and picker are pure and testable; chat-app is the
;; single place ids become actions, exactly as nodecode's shell maps a command
;; name to behaviour.
(ns faraday.harness.commands
  "Slash-command registry, matcher and resolver for the faraday terminal shell.

  A command is plain data: {:name :aliases :kind :description} plus either
  :action-id (for :action/:input) or :sheet-id (for :dialog). The shell maps
  those ids to behaviour; this namespace only matches, resolves and orders, so
  it stays pure and testable."
  (:require [clojure.string :as str]))

(defn- cmd [name aliases kind description & {:as extra}]
  (merge {:name name :aliases (vec aliases) :kind kind :description description}
         extra))

(def builtin-commands
  "The built-in slash commands. Kinds: :action (run now), :dialog (open a sheet),
  :input (ask for one line, then run). :headless? commands also answer outside
  the terminal shell (web console, chat room, eval) — the same contract nodecode
  keeps for its built-ins."
  [(cmd "/help" [] :dialog "Show commands and key bindings."
        :sheet-id :help :headless? true)
   (cmd "/clear" ["/new" "/reset"] :action "Start a fresh session."
        :action-id :clear :headless? true)
   (cmd "/compact" [] :action "Compact the session context."
        :action-id :compact)
   (cmd "/config" ["/conf"] :dialog "The resolved settings surface."
        :sheet-id :config :headless? true)
   (cmd "/cost" [] :action "Token usage for this session."
        :action-id :cost :headless? true)
   (cmd "/execs" [] :dialog "A running exec's output."
        :sheet-id :execs)
   (cmd "/export" [] :input "Save the session as Markdown."
        :action-id :export :placeholder "path (default: this folder)")
   (cmd "/fork" [] :action "Fork a new session at the current head."
        :action-id :fork)
   (cmd "/history" ["/recall"] :dialog "Pick a past prompt into the composer."
        :sheet-id :history)
   (cmd "/init" [] :action "Scaffold .faraday/settings.json."
        :action-id :init)
   (cmd "/memory" [] :dialog "Session memory notes."
        :sheet-id :memory)
   (cmd "/models" ["/model"] :dialog "Show or change the main and backup models."
        :sheet-id :models :headless? true)
   (cmd "/privacy" [] :action "Where faraday sends data."
        :action-id :privacy :headless? true)
   (cmd "/quit" ["/exit" "/bye"] :action "Exit faraday."
        :action-id :quit)
   (cmd "/redo" ["/forward"] :action "Return to the branch the last rewind left."
        :action-id :redo :headless? true)
   (cmd "/run" [] :input "Run a task or a loop manifest end to end."
        :action-id :run :placeholder "task-id or manifest.edn")
   (cmd "/settings" ["/preferences" "/options"] :dialog "Browse and change settings."
        :sheet-id :settings)
   (cmd "/spawn" [] :input "Fan out subagents from a loop manifest."
        :action-id :spawn :placeholder "manifest.edn (or N)")
   (cmd "/status" [] :action "Show session state."
        :action-id :status :headless? true)
   (cmd "/task" [] :input "Taskboard actions: add, list, start, close, show."
        :action-id :task :placeholder "add|list|start|close|show ...")
   (cmd "/board" [] :dialog "Kanban by status."
        :sheet-id :board)
   (cmd "/think" ["/effort" "/reasoning" "/thinking"] :dialog "The model's reasoning rungs."
        :sheet-id :effort :headless? true)
   (cmd "/tree" ["/agents" "/checkpoints"] :dialog "The agent fanout tree."
        :sheet-id :tree)
   (cmd "/undo" ["/rewind" "/rollback"] :action "Rewind the last turn."
        :action-id :undo :headless? true)])

(defn commands
  "The command registry, ordered by name (stable across calls)."
  []
  (sort-by :name builtin-commands))

(defn names
  "Every command name and alias, sorted, for help and completion."
  []
  (sort (mapcat (fn [c] (cons (:name c) (:aliases c))) builtin-commands)))

(defn- starts-with? [s prefix]
  (and (string? s)
       (str/starts-with? (str/lower-case s) (str/lower-case prefix))))

(defn- token-starts? [token prefix]
  "True when a registry token (name or alias, which may carry a leading slash)
  starts with prefix, comparing with the slash stripped and kept both ways."
  (or (starts-with? token prefix)
      (and (str/starts-with? token "/")
           (starts-with? (subs token 1) prefix))))

(defn match-commands
  "Commands whose name or an alias starts with `prefix` (the text after the
  leading slash). The empty prefix returns every command. Returns a vector of
  command maps carrying :matched, the spelling the operator typed, so the shell
  can complete an alias exactly as written."
  [prefix]
  (let [p (or prefix "")]
    (->> builtin-commands
         (keep (fn [c]
                 (cond
                   (token-starts? (:name c) p) (assoc c :matched (:name c))
                   :else
                   (when-let [al (first (filter #(token-starts? % p) (:aliases c)))]
                     (assoc c :matched al)))))
         (sort-by :name)
         vec)))

(defn- split-first-token [line]
  "[first-token rest] of a trimmed line, splitting at the first space; rest is
  nil when there is only one token. No regex, so it stays dependency-free."
  (let [line (str/trim line)
        ws (str/index-of line " ")]
    (if ws
      [(subs line 0 ws) (str/trim (subs line ws))]
      [line nil])))

(defn resolve-command
  "Resolve a slash line's first token to its command. Returns
  {:command c :rest rest-text :alias? bool} when the token is a command name or
  alias, else nil. /clear -> :rest nil; /run 12 -> :rest \"12\"."
  [line]
  (when (string? line)
    (let [[token rest] (split-first-token line)
          c (some (fn [c]
                    (cond
                      (= token (:name c)) [c false]
                      (some #(= token %) (:aliases c)) [c true]
                      :else nil))
                  builtin-commands)]
      (when c
        {:command (first c)
         :rest rest
         :alias? (second c)}))))

(defn filter-text
  "The dropdown filter for a draft: the text after the leading slash, up to the
  first space. nil when the draft does not begin with a slash."
  [draft]
  (when (and (string? draft) (str/starts-with? draft "/"))
    (let [body (subs draft 1)
          ws (str/index-of body " ")]
      (if ws (subs body 0 ws) body))))

(defn picker-step
  "One step of the command dropdown as pure data. Given the draft text and the
  current picker map, compute the next picker map for `key` (one of :open :close
  :up :down :home :end :type). :type clamps the selection back to 0 as the
  filter changes; the shell renders matches via match-commands."
  [draft picker key]
  (let [p (filter-text draft)
        n (count (match-commands p))]
    (case key
      :open (assoc picker :open? true :selected 0)
      :close (dissoc picker :open? :selected)
      :type (if (:open? picker) (assoc picker :selected 0) picker)
      :up (if (:open? picker)
            (assoc picker :selected (max 0 (dec (or (:selected picker) 0))))
            picker)
      :down (if (:open? picker)
              (assoc picker :selected (min (max 0 (dec n)) (inc (or (:selected picker) -1))))
              picker)
      :home (if (:open? picker) (assoc picker :selected 0) picker)
      :end (if (:open? picker) (assoc picker :selected (max 0 (dec n))) picker)
      picker)))

(defn completing?
  "Whether `draft` is still in command-name position: it starts with / and has
  no space yet, so a dropdown can offer completions. \"/he\" -> true,
  \"/help \" and \"/run 12\" -> false, plain text -> false. This is what the
  shell uses to decide whether the picker is on screen."
  [draft]
  (and (string? draft)
       (str/starts-with? draft "/")
       (not (str/includes? draft " "))))

(defn visible-window
  "The [start count] slice of `matches` to draw so `selected` stays inside and
  at most `max-rows` rows are shown — the picker scrolls instead of spilling
  past the bottom of a short terminal."
  [matches selected max-rows]
  (let [n (count matches)
        m (max 1 (long max-rows))]
    (if (<= n m)
      [0 n]
      (let [start (max 0 (min (- n m) (- (long selected) (quot m 2))))]
        [start m]))))

(defn help-lines
  "The /help sheet as rows: one per command, name + description. Aliases are
  folded into the name column as name (alias, alias)."
  []
  (mapv (fn [c]
          {:name (if (seq (:aliases c))
                   (str (:name c) " (" (str/join ", " (:aliases c)) ")")
                   (:name c))
           :description (:description c)
           :kind (:kind c)})
        (commands)))
