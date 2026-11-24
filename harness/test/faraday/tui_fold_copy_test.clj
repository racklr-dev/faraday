;; faraday-harness TUI fold + copy test: tool results and long code blocks fold
;; to a header (open on Enter/click), and Ctrl+Y copies the last answer through
;; OSC 52. Hermetic, no network, no terminal.
(ns jolt.test.tui-fold-copy
  (:require [faraday.harness.chat :as chat]
            [faraday.harness.console :as c]
            [glimmer.core :as ui]
            [glimmer-tui.core :as tui]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(defn- find-button [pred w]
  (when w
    (if (and (= :button (:tag @w)) (pred (get-in @w [:props :label])))
      w
      (some #(find-button pred %) (:children @w)))))

;; -- clipboard, pure -----------------------------------------------------------
;; (hi) base64 is aGk= ; the OSC 52 copy sequence is ESC ] 52 ; c ; <b64> BEL.
(let [s (#'chat/copy-sequence "hi")]
  (check "copy-sequence carries the OSC 52 header"
         true (str/starts-with? s "\u001b]52;c;"))
  (check "copy-sequence base64-encodes utf-8"
         "aGk=" (subs s (count "\u001b]52;c;") (- (count s) 1)))
  (check "copy-sequence ends with BEL"
         "\u0007" (subs s (dec (count s)))))

(let [s (#'chat/copy-sequence "hé")]
  (check "copy-sequence handles non-ascii" "aMOp" (subs s (count "\u001b]52;c;") (- (count s) 1))))

;; -- tool results fold ---------------------------------------------------------
(let [calls (atom [])
      fake-call (fn [_]
                  (swap! calls conj 1)
                  (if (= 1 (count @calls))
                    "{:tool-call {:name \"run_shell\" :args {:argv [\"echo\" \"hi\"]}}}"
                    "{:done \"ok\"}"))
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout "line one\nline two\nline three\n"})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 80 24)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  ;; type "hi", Enter, and let the tool loop finish
  (tui/press! 104)
  (tui/press! 105)
  (tui/press! 10)
  (Thread/sleep 150)
  (tui/drain!)
  (tui/frame!)
  (let [text (apply str (scr/lines screen))]
    (check "tool result folds to a one-line summary"
           true (str/includes? text "tool run_shell · ok · exit 0 · 3 lines"))
    (check "folded tool body is hidden" false (str/includes? text "line one")))
  (let [btn (find-button #(str/includes? % "tool run_shell") root)]
    (check-true "fold header button exists" (some? btn))
    ((get-in @btn [:props :on-click]))
    (tui/frame!)
    (let [text (apply str (scr/lines screen))]
      (check "clicking the header expands the body" true (str/includes? text "line one")))))

;; -- long code blocks fold -----------------------------------------------------
(let [code-lines (str/join "\n" (repeat 13 "(defn x [] 1)"))
      fake-call (fn [_] (str "{:done \"ok\"}"))
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 90 30)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  ;; Fold the 13-line code block by rendering the entry directly: the header is
  ;; a button, and the verbatim lines are absent until it is opened.
  (let [h ((var chat/entry-hiccup) (atom #{}) 60 false 0 {:role :assistant :text (str "```\n" code-lines "\n```")})
        s (pr-str h)]
    (check "entry-hiccup folds a long code block" true (str/includes? s "code · 13 lines"))
    (check "folded code body is hidden" false (str/includes? s "(defn x [] 1)"))))

;; -- keyboard: Tab focuses the fold header, Enter opens it ---------------------
(let [calls (atom 0)
      fake-call (fn [_]
                  (swap! calls inc)
                  (if (= 1 @calls)
                    "{:tool-call {:name \"run_shell\" :args {:argv [\"echo\" \"hi\"]}}}"
                    "{:done \"done\"}"))
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout "secret line"})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 80 24)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (tui/press! 104) ; h
  (tui/press! 105) ; i
  (tui/press! 10)  ; Enter -> send
  (Thread/sleep 150)
  (tui/drain!)
  (tui/frame!)
  (check "kb: folded tool body hidden before open"
         false (str/includes? (apply str (scr/lines screen)) "secret line"))
  (tui/press! {:type :tab})   ; focus leaves the composer for the fold header
  (tui/frame!)
  (tui/press! {:type :enter}) ; activate the focused fold header
  (tui/frame!)
  (check "kb: Tab+Enter expands the fold"
         true (str/includes? (apply str (scr/lines screen)) "secret line")))

;; -- long prose folds; the latest answer stays open ---------------------------
(let [prose (str/join "\n" (repeat 10 "A rather long line of assistant prose that wraps around."))
      calls (atom 0)
      fake-call (fn [_]
                  (swap! calls inc)
                  (if (= 1 @calls)
                    (pr-str {:done prose})
                    (pr-str {:done "short final answer"})))
      fake-exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
      app (chat/chat-app {:call fake-call :exec fake-exec :wrap 40})
      root (w/node :window {})
      screen (scr/buffer-screen 90 30)]
  (tui/attach! root screen)
  (ui/mount root :window [app])
  (tui/frame!)
  (doseq [_ (range 2)]            ; two turns: a long answer, then a short one
    (tui/press! 104)
    (tui/press! 10)
    (Thread/sleep 150)
    (tui/drain!)
    (tui/frame!))
  (let [text (apply str (scr/lines screen))]
    (check "earlier long prose folds to an assistant summary"
           true (str/includes? text "assistant · "))
    (check "earlier folded prose body is hidden"
           false (str/includes? text "wraps around"))
    (check "the latest answer stays open"
           true (str/includes? text "short final answer")))
  (let [btn (find-button #(str/includes? % "assistant · ") root)]
    (check-true "prose fold header button exists" (some? btn))
    ((get-in @btn [:props :on-click]))
    (tui/frame!)
    (check "prose expands on click"
           true (str/includes? (apply str (scr/lines screen)) "wraps around"))))

(println "ALL TUI FOLD/COPY TESTS PASSED")
(shutdown-agents)
