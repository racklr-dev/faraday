;; faraday.harness chat test: the /help modal dialog.
;; Mounts chat-app headlessly and drives it with tui/press!: /help opens a
;; modal dialog, typing filters the list, down+enter activates a row, and esc
;; closes it — the nodecode-style sheet over the composer.
;; Run: cd harness && jolt run test/faraday/tui_dialog_test.clj
(ns jolt.test.tui-dialog
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

(defn- screen-text [screen] (apply str (scr/lines screen)))

(defn- find-widget [tag w]
  (when w
    (if (= tag (:tag @w))
      w
      (some #(find-widget tag %) (:children @w)))))

(defn- mount! [screen]
  (let [app (chat/chat-app {:call (fn [_] "{:done \"ok\"}")
                            :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                            :wrap 40})
        root (w/node :window {})]
    (tui/attach! root screen)
    (ui/mount root :window [app])
    (tui/frame!)
    root))

(defn- open-help! [screen]
  ;; type /help and run it, one frame per key like the real event loop
  (doseq [code [47 104 101 108 112]] (tui/press! code) (tui/frame!))
  (tui/press! 10)
  (tui/frame!))

;; -- open, filter, close ------------------------------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  (open-help! screen)
  (let [text (screen-text screen)]
    (check "opening /help shows the dialog title" true (str/includes? text "help"))
    (check "dialog lists commands" true (str/includes? text "/clear"))
    (check "dialog shows its key footer" true (str/includes? text "esc close")))
  ;; typing narrows the list to commands matching q (just /quit)
  (tui/press! 113)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "typing filters the list" true (str/includes? text "/quit"))
    (check "filter drops non-matching rows" false (str/includes? text "/clear")))
  ;; backspace clears the filter again
  (tui/press! 263)
  (tui/frame!)
  (check "backspace clears the filter" true (str/includes? (screen-text screen) "/clear"))
  ;; esc closes the dialog and leaves the composer empty
  (tui/press! {:type :escape})
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "esc closes the dialog" false (str/includes? text "esc close"))
    (check "esc leaves the composer empty" "" (get-in @(find-widget :entry root) [:props :text]))))

;; -- navigate and activate ----------------------------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  (open-help! screen)
  ;; down moves the cursor from /board to /clear (index 1), enter runs it
  (tui/press! {:type :down})
  (tui/frame!)
  (tui/press! 10)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "down+enter activates the selected command" true (str/includes? text "session cleared"))
    (check "activating closes the dialog" false (str/includes? text "esc close"))))

(println "ALL TUI DIALOG TESTS PASSED")
