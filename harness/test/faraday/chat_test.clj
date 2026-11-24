;; faraday-harness chat test: word-wrap, build-messages/format-tool-result/
;; parse-action, and the agentic component mounted headlessly (in-memory screen,
;; injected :call/:exec) — a user message runs a tool, the transcript shows the
;; tool call, its result, and the final answer, and the draft clears.
;; Run: cd harness && jolt run test/faraday/chat_test.clj
(ns jolt.test.chat
  (:require [faraday.harness.chat :as chat]
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

;; -- markdown highlighter --------------------------------------------------------
(let [md #'chat/markdown-lines]
  (check "md: heading" [:heading "Title"] (first (md "# Title")))
  (check "md: code fence" [:code "x"] (first (md "```\nx\n```")))
  (check "md: bold stripped" [:text "hello world"] (first (md "**hello** world"))))

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
(check "boot-context-lines with board not ready"
       ["Working directory: /x"
        "Project root: /x"
        "Taskboard: NOT initialized — board/scope/hook tools will error; use run_shell instead (or run `faraday init` first)."]
       (chat/boot-context-lines {:cwd "/x" :proj-root "/x" :board-ready? false}))

(check "boot-context-lines with board ready"
       ["Working directory: /x"
        "Project root: /x"
        "Taskboard: ready — board/scope/hook/pool tools work."]
       (chat/boot-context-lines {:cwd "/x" :proj-root "/x" :board-ready? true}))

(let [d (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "fctx" (make-array java.nio.file.attribute.FileAttribute 0))))
      _ (spit (str d "/b.txt") "")
      _ (spit (str d "/a.txt") "")]
  (check "top-level-summary sorts names"
         "a.txt b.txt"
         (chat/top-level-summary d)))

  (println "chat_test OK"))

(shutdown-agents)
