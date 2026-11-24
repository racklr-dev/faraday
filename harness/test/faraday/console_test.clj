;; faraday-harness console test: the operator console (chat + dashboard modes)
;; renders the chat component in the chat mode and the board sections in the
;; dashboard modes, switched by the :view ratom. Hermetic (buffer-screen, fake
;; call/exec seams, no network/KVM).
(ns jolt.test.console
  (:require [faraday.harness.console :as c]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
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

(let [chat-opts {:call (fn [_] "{:done \"ok\"}")
                 :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                 :system "S" :max-turns 1}
      view (r/atom "chat")
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [(fn [] (c/app chat-opts view))])
  (tui/frame!)
  (let [text (apply str (scr/lines screen))]
    (check-true "tab bar has Chat and Overview"
                (and (str/includes? text "Chat") (str/includes? text "Overview")))
    (check-true "chat mode shows the conversation frame"
                (str/includes? text "conversation")))
  (reset! view "overview")
  (tui/drain!)
  (tui/frame!)
  (let [text (apply str (scr/lines screen))]
    (check-true "overview mode shows plane stats" (str/includes? text "plane"))
    (check "overview mode hides the conversation frame"
           false (str/includes? text "conversation"))))

(println "ALL CONSOLE TESTS PASSED")
(shutdown-agents)
