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
  (check "folding prepends a summary" true (str/includes? (:content (second msgs)) "folded"))
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
  (println "chat_test OK"))

(shutdown-agents)
