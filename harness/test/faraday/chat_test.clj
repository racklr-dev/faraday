;; faraday-harness chat test: word-wrap, build-messages/format-tool-result/
;; parse-action, and the agentic component mounted headlessly (in-memory screen,
;; injected :call/:exec) — a user message runs a tool, the transcript shows the
;; tool call, its result, and the final answer, and the draft clears.
;; Run: cd harness && jolt run test/faraday/chat_test.clj
(ns jolt.test.chat
  (:require [faraday.harness :as h]
            [faraday.harness.chat :as chat]
            [clojure.string :as str]
            [glimmer.core :as ui]
            [glimmer-tui.core :as tui]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- word-wrap ----------------------------------------------------------------
(check "wrap: short string" ["short"] (chat/word-wrap "short" 20))
(check "wrap: at width"
       ["hello world" "this is a" "test of" "wrapping"]
       (chat/word-wrap "hello world this is a test of wrapping" 12))
(check "wrap: hard-breaks a long word"
       ["supercal" "ifragili" "stic"]
       (chat/word-wrap "supercalifragilistic" 8))
(check "wrap: preserves newlines"
       ["line1" "second" "line here"]
       (chat/word-wrap "line1\nsecond line here" 10))

;; -- build-messages ------------------------------------------------------------
(check "build-messages: system+user"
       [{:role "system" :content "S"} {:role "user" :content "hi"}]
       (chat/build-messages "S" [{:role :user :text "hi"}]))

(check "build-messages: tool-call becomes assistant EDN"
       [{:role "system" :content "S"}
        {:role "assistant" :content (pr-str {:tool-call {:name "run_shell" :args {:argv ["ls"]}}})}]
       (chat/build-messages "S" [{:role :tool-call :name "run_shell" :args {:argv ["ls"]}}]))

(check "build-messages: tool-result becomes a user turn"
       [{:role "system" :content "S"}
        {:role "user" :content "tool run_shell finished with status ok, exit 0:\nout\n"}]
       (chat/build-messages "S" [{:role :tool-result :name "run_shell" :status :ok :exit 0 :text "out\n"}]))

;; -- format-tool-result ---------------------------------------------------------
(check "format-tool-result includes status+exit+output"
       "tool run_shell finished with status ok, exit 0:\nout"
       (chat/format-tool-result {:name "run_shell" :status :ok :exit 0 :text "out"}))

;; -- parse-action ---------------------------------------------------------------
(check "parse-action: done map" {:done "ok"} (chat/parse-action "{:done \"ok\"}"))
(check "parse-action: tool-call map"
       {:tool-call {:name "run_shell" :args {:argv ["ls"]}}}
       (chat/parse-action "{:tool-call {:name \"run_shell\" :args {:argv [\"ls\"]}}}"))
(check "parse-action: plain text is nil" nil (chat/parse-action "just some words"))
(check "parse-action: fenced EDN is stripped"
       {:done "ok"}
       (chat/parse-action "```clojure\n{:done \"ok\"}\n```"))

;; -- undo/redo (branch rewind) ------------------------------------------------
(check "last-user-index: nil with no user turn"
       nil
       (chat/last-user-index [{:role :assistant :text "ok"}]))
(check "last-user-index: last user entry"
       2
       (chat/last-user-index [{:role :user :text "a"}
                              {:role :assistant :text "b"}
                              {:role :user :text "c"}]))
(check "undo-turn: splits the last user turn"
       [[{:role :user :text "a"} {:role :assistant :text "b"}]
        [{:role :user :text "c"} {:role :assistant :text "d"} {:role :tool-call :name "x"}]]
       (chat/undo-turn [{:role :user :text "a"} {:role :assistant :text "b"}
                        {:role :user :text "c"} {:role :assistant :text "d"}
                        {:role :tool-call :name "x"}]))
(check "undo-turn: nil with no user turn"
       nil
       (chat/undo-turn [{:role :assistant :text "ok"}]))
(check "history-undo moves the last turn onto :redo"
       {:messages [{:role :user :text "a"} {:role :assistant :text "b"}]
        :redo [[{:role :user :text "c"} {:role :assistant :text "d"}]]}
       (chat/history-undo {:messages [{:role :user :text "a"} {:role :assistant :text "b"}
                                      {:role :user :text "c"} {:role :assistant :text "d"}]}))
(check "history-undo nil with nothing to rewind"
       nil
       (chat/history-undo {:messages [{:role :assistant :text "ok"}]}))
(check "history-redo returns the last rewound branch"
       {:messages [{:role :user :text "a"} {:role :user :text "c"} {:role :assistant :text "d"}]
        :redo []}
       (chat/history-redo {:messages [{:role :user :text "a"}]
                           :redo [[{:role :user :text "c"} {:role :assistant :text "d"}]]}))
(check "history-redo nil with nothing rewound"
       nil
       (chat/history-redo {:messages [{:role :user :text "a"}]}))
(check "undo then redo round-trips"
       {:messages [{:role :user :text "a"} {:role :assistant :text "b"}]
        :redo []}
       (-> {:messages [{:role :user :text "a"} {:role :assistant :text "b"}]
            :redo []}
           chat/history-undo
           chat/history-redo))

;; -- transcript->markdown -----------------------------------------------------
(check "transcript->markdown: user+assistant"
       "# faraday session\n\n## user\n\nhi\n\n## assistant\n\nok"
       (chat/transcript->markdown [{:role :user :text "hi"}
                                   {:role :assistant :text "ok"}]))
(check "transcript->markdown: tool-call fences name + argv"
       "# faraday session\n\n## tool-call\n\n```\n$ run_shell ls -la\n```"
       (chat/transcript->markdown [{:role :tool-call :name "run_shell" :args {:argv ["ls" "-la"]}}]))
(check "transcript->markdown: tool-result reuses format-tool-result"
       "# faraday session\n\n## tool-result\n\ntool run_shell finished with status ok, exit 0:\nout"
       (chat/transcript->markdown [{:role :tool-result :name "run_shell" :status :ok :exit 0 :text "out"}]))
(check "transcript->markdown: system entry"
       "# faraday session\n\n## system\n\nContext so far: x"
       (chat/transcript->markdown [{:role :system :text "Context so far: x"}]))

;; -- export-session! ----------------------------------------------------------
(let [written (atom nil)]
  (chat/export-session! [{:role :user :text "hi"}] "t1" "/tmp/faraday-export-test.md"
                        {:spit-fn (fn [p md] (reset! written {:p p :md md}))})
  (check "export-session! writes markdown via seam"
         {:p "/tmp/faraday-export-test.md"
          :md "# faraday session\n\n## user\n\nhi"}
         @written))
(let [written (atom nil)]
  (chat/export-session! [{:role :user :text "hi"}] "t1" "" {:cwd "/tmp" :spit-fn (fn [p _] (reset! written p))})
  (check "export-session! blank path -> cwd/faraday-<id>.md"
         "/tmp/faraday-t1.md"
         @written))
(let [written (atom nil)]
  (chat/export-session! [{:role :user :text "hi"}] "t1" "/tmp" {:spit-fn (fn [p _] (reset! written p))})
  (check "export-session! directory -> default filename inside"
         "/tmp/faraday-t1.md"
         @written))

;; -- taskboard pure pieces -------------------------------------------------------
(check "parse-task-line: add with flags"
       {:sub "add" :positional ["fix" "the" "bug"] :flags {:priority "high" :scope "src/db"}}
       (chat/parse-task-line "add fix the bug --priority high --scope src/db"))
(check "parse-task-line: --k=v form"
       {:sub "list" :positional [] :flags {:status "open"}}
       (chat/parse-task-line "list --status=open"))
(check "parse-task-line: blank defaults sub to nil"
       {:sub nil :positional [] :flags {}}
       (chat/parse-task-line "  "))
(check "parse-task-line: no flags"
       {:sub "show" :positional ["faraday-1"] :flags {}}
       (chat/parse-task-line "show faraday-1"))

(check "task-row carries id/priority/status"
       {:label "fix the thing" :value "faraday-1  [high]  open" :task-id "faraday-1"}
       (chat/task-row {:id "faraday-1" :title "fix the thing" :priority "high" :status "open"}))

(check "board-rows orders by canonical column then priority"
       ["faraday-2" "faraday-1" "faraday-3"]
       (mapv :task-id
             (chat/board-rows [{:id "faraday-3" :title "done" :priority "high" :status "done"}
                               {:id "faraday-1" :title "open" :priority "low" :status "open"}
                               {:id "faraday-2" :title "open" :priority "high" :status "open"}])))

(check "task-detail-rows shows the key fields and clips body"
       ["id" "title" "status" "priority" "body"]
       (mapv :label (chat/task-detail-rows {:id "faraday-1" :title "t" :status "open"
                                            :priority "normal" :body "a long body"})))

;; -- markdown highlighter --------------------------------------------------------
(let [md #'chat/markdown-lines]
  (check "md: heading" [:heading "Title"] (first (md "# Title")))
  (check "md: code fence" [:code "x"] (first (md "```\nx\n```")))
  (check "md: bold stripped" [:text "hello world"] (first (md "**hello** world")))
  (check "md: inline code stripped" [:text "run bb"] (first (md "run `bb`")))
  (check "md: link collapses to label" [:text "docs"] (first (md "[docs](https://x)")))
  (check "md: plus bullet" [:bullet "item"] (first (md "+ item")))
  (check "md: numbered item" [:numbered "1. item"] (first (md "1. item")))
  (check "md: italic stripped" [:text "italic"] (first (md "*italic*")))
  (check "md: underscore italic stripped" [:text "under"] (first (md "_under_")))
  (check "md: snake_case intact" [:text "snake_case_here"] (first (md "snake_case_here")))
  (check "md: bold in heading" [:heading "bold"] (first (md "## **bold**"))))

(let [segs (chat/entry-segments {:role :assistant :text "para one\n\n- item a\n- item b\n\npara two"} 40)]
  (check "entry-segments: tight list, single blank rows"
         [{:t :line :kind :text} {:t :spacer} {:t :line :kind :bullet}
          {:t :line :kind :bullet} {:t :spacer} {:t :line :kind :text}]
         (mapv #(select-keys % [:t :kind]) segs)))

(let [ww #'chat/wrap-width]
  (check "wrap-width: explicit --wrap wins" 60 (ww {:wrap 60} 200))
  (check "wrap-width: live cols minus scrollbar+inset" 178 (ww {} 180))
  (check "wrap-width: headless fallback" 92 (ww {} nil)))

(let [msgs (chat/build-messages "S" (vec (repeat 45 {:role :user :text "hi"})) :context-budget 40)]
  (check "folding prepends a summary" true (str/includes? (:content (second msgs)) "earlier context"))
  (check "folding caps the transcript" 42 (count msgs)))

;; -- the component, headless -----------------------------------------------------
(defn- find-widget [tag w]
  (when w
    (if (= tag (:tag @w))
      w
      (some #(find-widget tag %) (:children @w)))))

(let [calls (atom [])
      fake-call (fn [msgs]
                  (swap! calls conj (mapv :role msgs))
                  (if (= 1 (count @calls))
                    "{:tool-call {:name \"run_shell\" :args {:argv [\"echo\" \"hi\"]}}}"
                    "{:done \"did it\"}"))
      fake-exec (fn [name args]
                  {:status :ok :exit 0 :stdout "hi\n" :stderr ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  ;; type "hi" and press Enter
  (tui/press! 104)
  (tui/press! 105)
  (tui/press! 10)
  ;; let the agentic future finish, then apply its queued re-renders
  (Thread/sleep 150)
  (tui/drain!)
  (tui/frame!)
  (let [text (apply str (scr/lines screen))]
    (check "transcript shows the user message prefixed" true (str/includes? text "you> hi"))
    (check "transcript shows the tool call" true (str/includes? text "→"))
    (check "transcript shows the final answer" true (str/includes? text "did it"))
    (check "two model calls (tool then done)" 2 (count @calls))
    (check "draft cleared after send" "" (get-in @(find-widget :entry root) [:props :text])))

;; -- composer recall: ctrl+p/n walk past user prompts ------------------------
(let [fake-call (fn [_] "{:done \"ok\"}")
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)
      entry-text (fn [] (get-in @(find-widget :entry root) [:props :text]))]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (doseq [c (map int "one")] (tui/press! c))
  (tui/press! 10)
  (Thread/sleep 150) (tui/drain!) (tui/frame!)
  (doseq [c (map int "two")] (tui/press! c))
  (tui/press! 10)
  (Thread/sleep 150) (tui/drain!) (tui/frame!)
  (check "composer empty after two sends" "" (entry-text))
  (tui/press! 16) (tui/drain!) (tui/frame!)
  (check "ctrl+p recalls the latest prompt" "two" (entry-text))
  (tui/press! 16) (tui/drain!) (tui/frame!)
  (check "ctrl+p again recalls the previous prompt" "one" (entry-text))
  (tui/press! 16) (tui/drain!) (tui/frame!)
  (check "ctrl+p at oldest prompt stays" "one" (entry-text))
  (tui/press! 14) (tui/drain!) (tui/frame!)
  (check "ctrl+n returns to the newer prompt" "two" (entry-text))
  (tui/press! 14) (tui/drain!) (tui/frame!)
  (check "ctrl+n past newest clears the composer" "" (entry-text)))

;; -- run_jolt routes to the executor ------------------------------------------
(let [exec-names (atom [])
      fake-call (fn [_]
                  (if (empty? @exec-names)
                    "{:tool-call {:name \"run_jolt\" :args {:code \"(println 1)\"}}}"
                    "{:done \"ok\"}"))
      fake-exec (fn [name args]
                  (swap! exec-names conj name)
                  {:status :ok :exit 0 :stdout "1\n" :stderr ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (tui/press! 104)   ; h
  (tui/press! 10)    ; enter
  (Thread/sleep 150) (tui/drain!) (tui/frame!)
  (check "run_jolt routes to the executor" ["run_jolt"] @exec-names)
  (check "run_jolt result reaches the transcript" true
         (str/includes? (apply str (scr/lines screen)) "1")))

;; -- scroll: arrows/page keys scroll the transcript even though the composer
;; -- is always focused; a long transcript overflows and auto-pins to the bottom
(let [fake-call (fn [_] "{:done \"ok\"}")
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (dotimes [_ 10]
    (tui/press! 104) (tui/press! 10)   ; send "h" x10 -> overflow
    (Thread/sleep 80) (tui/drain!) (tui/frame!))
  (let [n (some (fn [x] (when (= :scroll (w/container-kind (:tag x))) x))
                (w/walk (:tree @@#'tui/app)))]
    (check "long transcript overflows the viewport" true
           (> (:h (:content n 0)) (:h (:viewport n 0)))))
  (let [before (apply str (scr/lines screen))]
    (tui/press! {:type :up}) (tui/drain!) (tui/frame!)   ; up detaches follow, scrolls up
    (check "up arrow scrolls the transcript" true
           (not= before (apply str (scr/lines screen)))))
  (let [scrolled-up (apply str (scr/lines screen))]
    (tui/press! {:type :page-down}) (tui/drain!) (tui/frame!) ; pgdn jumps back to bottom
    (check "pgdn jumps back to the bottom" true
           (not= scrolled-up (apply str (scr/lines screen))))))

;; -- composer kill/yank: ctrl+a/e/k + ctrl+y (readline loop) ----------------
(let [fake-call (fn [_] "{:done \"ok\"}")
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)
      entry-text #(get-in @(find-widget :entry root) [:props :text])]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (doseq [c (map int "hello")] (tui/press! c))
  (check "typed text lands in the composer" "hello" (entry-text))
  (tui/press! 1)    ; ctrl+a -> start
  (tui/press! 11)   ; ctrl+k -> kill to end
  (check "ctrl+k clears the composer and saves the kill" "" (entry-text))
  (tui/press! 25)   ; ctrl+y -> yank
  (check "ctrl+y yanks the killed text back" "hello" (entry-text)))

;; -- send while busy interrupts the running tool and stages the message ------
(let [calls (atom 0)
      saw-interrupt (atom false)
      fake-call (fn [_]
                  (if (zero? @calls)
                    (do (swap! calls inc) "{:tool-call {:name \"run_shell\" :args {:argv [\"slow\"]}}}")
                    "{:done \"ok\"}"))
      fake-exec (fn [_ _]
                  (loop [i 0]
                    (cond
                      @chat/*interrupt*
                      (do (reset! saw-interrupt true)
                          {:status :error :error "interrupted" :interrupted? true})

                      (>= i 400)
                      {:status :ok :exit 0 :stdout "done"}

                      :else
                      (do (Thread/sleep 10) (recur (inc i))))))
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (doseq [c (map int "go")] (tui/press! c))
  (tui/press! 10)
  (Thread/sleep 120)   ; the tool is now mid-flight
  (doseq [c (map int "steer")] (tui/press! c))
  (tui/press! 10)      ; send while busy -> interrupt + stage
  (Thread/sleep 300) (tui/drain!) (tui/frame!)
  (check "send while busy interrupts the running tool" true @saw-interrupt)
  (check "staged message lands as the next turn" true
         (str/includes? (apply str (scr/lines screen)) "steer")))

;; -- fatal tool errors stop the turn -----------------------------------------
(check "group-members parses /etc/group"
       #{"jm" "bob"}
       (chat/group-members ["kvm:x:108:jm,bob"] "kvm"))
(check "group-members missing group is nil"
       nil
       (chat/group-members ["kvm:x:108:jm"] "docker"))
(check "group-members empty members is empty set"
       #{}
       (chat/group-members ["kvm:x:108:"] "kvm"))

(let [calls (atom 0)
      fake-call (fn [msgs] (swap! calls inc) "{:tool-call {:name \"run_shell\" :args {:argv [\"ls\"]}}}")
      fake-exec (fn [name args] {:status :error :error "sandbox exec failed (kvm)" :fatal? true})
      sess (chat/chat-session {:call fake-call :exec fake-exec :max-stall 8 :max-turns 8})
      _ ((:send! sess) "look at this")
      _ (Thread/sleep 200)
      st @(:state sess)]
  (check "fatal tool error stops the turn" "sandbox exec failed (kvm)" (:error st))
  (check "fatal tool error makes one model call" 1 @calls)
  (check "fatal tool error records call+result+answer" 4 (count (:messages st)))
  (check "fatal tool error ends with an assistant answer" "sandbox exec failed (kvm)" (-> st :messages last :text)))

(check "transcript drops blank prose lines"
       ["hi"]
       (map :text (chat/transcript-segments [{:role :user :text "hi"}
                                             {:role :assistant :text ""}
                                             {:role :assistant :text "\n\n"}]
                                            40)))

(check "system-with-context injects context before facts"
       "base\n\nContext:\nc1\nc2\n\nYou remember these facts from earlier sessions:\n{:a 1}"
       (chat/system-with-context "base" ["c1" "c2"] {:a 1}))

(check "system-with-context no context no facts"
       "base"
       (chat/system-with-context "base" nil nil))

;; -- max turns force a final answer instead of a dead stop ---------------------
(let [calls (atom 0)
      fake-call (fn [msgs]
                  (swap! calls inc)
                  (if (some #(str/includes? (str (:content %)) "do not call tools") msgs)
                    "{:done \"best effort answer\"}"
                    "{:tool-call {:name \"run_shell\" :args {:argv [\"ls\"]}}}"))
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout "x"})
      sess (chat/chat-session {:call fake-call :exec fake-exec :max-turns 2})
      _ ((:send! sess) "do the thing")
      _ (Thread/sleep 250)
      st @(:state sess)]
  (check "max-turns forces a final answer"
         "best effort answer"
         (-> st :messages last :text))
  (check "max-turns clears the error banner" nil (:error st))
  (check "max-turns clears busy" false (:busy? st)))

;; -- working context lines ------------------------------------------------------
(check "boot-context-lines with board not ready (cwd = project root)"
       ["Working directory (project root): /x"
        "Taskboard: NOT initialized — board/scope/hook tools will error; use run_shell instead (or run `faraday init` first)."]
       (chat/boot-context-lines {:cwd "/x" :proj-root "/x" :board-ready? false}))

(check "boot-context-lines with board ready (cwd = project root)"
       ["Working directory (project root): /x"
        "Taskboard: ready — board/scope/hook/pool tools work."]
       (chat/boot-context-lines {:cwd "/x" :proj-root "/x" :board-ready? true}))

(check "boot-context-lines full context"
       ["Working directory: /repo/sub"
        "Project root: /repo"
        "Project: a control plane"
        "Taskboard: ready — board/scope/hook/pool tools work."
        "Git: main · dirty (1 changed, 1 untracked)"
        "Recent commits: abc123 first | def456 second"
        "Open tasks:\n  t-1 [high][open] fix the thing"]
       (chat/boot-context-lines {:cwd "/repo/sub"
                                 :proj-root "/repo"
                                 :board-ready? true
                                 :project-desc "a control plane"
                                 :git-summary "main · dirty (1 changed, 1 untracked)"
                                 :recent-commits "abc123 first | def456 second"
                                 :tasks ["t-1 [high][open] fix the thing"]}))

(let [d (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "fctx" (make-array java.nio.file.attribute.FileAttribute 0))))
      _ (spit (str d "/b.txt") "")
      _ (spit (str d "/a.txt") "")]
  (check "top-level-summary sorts names"
         "a.txt b.txt"
         (chat/top-level-summary d)))

(let [d (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "fctx" (make-array java.nio.file.attribute.FileAttribute 0))))
      _ (spit (str d "/README.md") "# Title\n\nA one-line project descriptor.\n")
      _ (spit (str d "/other.txt") "")]
  (check "readme-desc skips the heading and reads the description"
         "A one-line project descriptor."
         (chat/readme-desc d)))

(let [d (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "fctx" (make-array java.nio.file.attribute.FileAttribute 0))))]
  (check "git-status outside a repo is nil" nil (chat/git-status d))
  (check "git-summary outside a repo is nil" nil (chat/git-summary d))
  (h/run-command ["git" "-C" d "init" "-q"] 4000)
  (spit (str d "/f.txt") "x")
  (h/run-command ["git" "-C" d "-c" "user.email=a@b.c" "-c" "user.name=t" "add" "f.txt"] 4000)
  (h/run-command ["git" "-C" d "-c" "user.email=a@b.c" "-c" "user.name=t" "commit" "-qm" "first"] 4000)
  (check "git-status clean repo" "clean" (chat/git-status d))
  (spit (str d "/f.txt") "y")
  (check "git-status dirty repo counts the change" "dirty (1 changed)" (chat/git-status d))
  (check "git-summary joins branch and status" true (str/includes? (chat/git-summary d) "dirty (1 changed)")))

  (println "chat_test OK"))

(shutdown-agents)
