;; faraday-harness console test: the terminal client is the chat surface
;; (the dashboard is the web gateway's client). Hermetic (buffer-screen, fake
;; call/exec seams, no network/KVM).
(ns jolt.test.console
  (:require [faraday.harness.console :as c]
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

(let [chat-opts {:call (fn [_] "{:done \"ok\"}")
                 :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                 :system "S" :max-turns 1}
      root (w/node :window {})
      screen (scr/buffer-screen 60 20)]
  (tui/attach! root screen)
  (ui/mount root :window [(c/app chat-opts)])
  (tui/frame!)
  (let [text (apply str (scr/lines screen))]
    (check-true "chat renders the composer" (str/includes? text "Ready"))
    (check-true "chat renders the status line" (str/includes? text "model"))
    (check-true "chat renders the quit hint" (str/includes? text "ctrl+c quit"))))

(println "ALL CONSOLE TESTS PASSED")
(shutdown-agents)