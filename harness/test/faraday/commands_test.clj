;; faraday.harness.commands test: the slash-command registry, matcher,
;; resolver and dropdown picker are pure data, so they pin without a terminal.
;; Run: cd faraday-harness && jolt run test/faraday/commands_test.clj
(ns jolt.test.commands
  (:require [faraday.harness.commands :as cmds]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- registry ----------------------------------------------------------------
(check "registry is non-empty and sorted by name"
        (sort-by :name cmds/builtin-commands)
        (cmds/commands))
(check "every command has a name, aliases vector, kind and description"
        true
        (every? (fn [c]
                  (and (string? (:name c))
                       (vector? (:aliases c))
                       (contains? #{:action :dialog :input} (:kind c))
                       (string? (:description c))))
                cmds/builtin-commands))
(check "names returns every name and alias sorted"
        (sort (mapcat (fn [c] (cons (:name c) (:aliases c))) cmds/builtin-commands))
        (cmds/names))

;; -- matching ----------------------------------------------------------------
(check "empty prefix matches everything"
        (count cmds/builtin-commands)
        (count (cmds/match-commands "")))
(check "prefix /se matches settings"
        ["/settings"]
        (mapv :name (cmds/match-commands "se")))
(check "prefix /mod matches models only"
        ["/models"]
        (mapv :name (cmds/match-commands "mod")))
(check "prefix /t matches task, think, tree (sorted)"
        ["/task" "/think" "/tree"]
        (mapv :name (cmds/match-commands "t")))
(check "alias /new matches clear with matched alias"
        [{:matched "/new" :name "/clear"}]
        (mapv #(select-keys % [:matched :name]) (cmds/match-commands "new")))
(check "no match for /zzz"
        []
        (cmds/match-commands "zzz"))

;; -- resolving ---------------------------------------------------------------
(check "/help resolves to the help dialog"
        {:command "/help" :rest nil :alias? false}
        (update (cmds/resolve-command "/help") :command :name))
(check "/run 12 resolves run with rest"
        {:command "/run" :rest "12" :alias? false}
        (update (cmds/resolve-command "/run 12") :command :name))
(check "/new resolves clear via alias"
        {:command "/clear" :rest nil :alias? true}
        (update (cmds/resolve-command "/new") :command :name))
(check "/agents resolves tree via alias"
        {:command "/tree" :rest nil :alias? true}
        (update (cmds/resolve-command "/agents") :command :name))
(check "unknown command resolves to nil"
        nil
        (cmds/resolve-command "/nope"))
(check "a plain sentence is not a command"
        nil
        (cmds/resolve-command "not a command"))

;; -- filter + picker ---------------------------------------------------------
(check "filter-text extracts the token after the slash"
        "mod"
        (cmds/filter-text "/mod"))
(check "filter-text stops at the first space"
        "run"
        (cmds/filter-text "/run 12"))
(check "filter-text of a bare slash is empty"
        ""
        (cmds/filter-text "/"))
(check "filter-text is nil when not a slash line"
        nil
        (cmds/filter-text "hello"))

(let [open (cmds/picker-step "/" {} :open)]
  (check "open sets open? and selects 0" {:open? true :selected 0} open)
  (check "down moves selection to 1"
          {:open? true :selected 1}
          (cmds/picker-step "/" open :down))
  (check "down clamps to the last row"
          {:open? true :selected (dec (count cmds/builtin-commands))}
          (cmds/picker-step "/" (assoc open :selected 999) :down))
  (check "up clamps to 0"
          {:open? true :selected 0}
          (cmds/picker-step "/" (assoc open :selected 0) :up))
  (check "home selects 0"
          {:open? true :selected 0}
          (cmds/picker-step "/" (assoc open :selected 5) :home))
  (check "end selects the last row"
          {:open? true :selected (dec (count cmds/builtin-commands))}
          (cmds/picker-step "/" (assoc open :selected 0) :end))
  (check "typing resets selection to 0"
          {:open? true :selected 0}
          (cmds/picker-step "/m" (assoc open :selected 4) :type))
  (check "close drops the picker"
          {}
          (cmds/picker-step "/" open :close)))

(check "help-lines has one row per command with name and description"
        (count cmds/builtin-commands)
        (count (cmds/help-lines)))

;; -- completing? ---------------------------------------------------------------
(check "completing?: bare slash" true (cmds/completing? "/"))
(check "completing?: prefix" true (cmds/completing? "/mod"))
(check "completing?: trailing space ends it" false (cmds/completing? "/mod "))
(check "completing?: argument ends it" false (cmds/completing? "/run 12"))
(check "completing?: plain text" false (cmds/completing? "hello"))
(check "completing?: nil" false (cmds/completing? nil))

;; -- visible-window -------------------------------------------------------------
(check "window: fits whole list"
        [0 (count cmds/builtin-commands)]
        (cmds/visible-window (cmds/match-commands "") 0 25))
(check "window: caps and centers the selection"
        [1 8]
        (cmds/visible-window (vec (range 20)) 5 8))
(check "window: clamps start at the top"
        [0 8]
        (cmds/visible-window (vec (range 20)) 1 8))
(check "window: clamps start at the bottom"
        [12 8]
        (cmds/visible-window (vec (range 20)) 19 8))
(check "window: selection above the window stays pinned"
        [0 8]
        (cmds/visible-window (vec (range 20)) 0 8))

(println "ALL COMMANDS TESTS PASSED")
