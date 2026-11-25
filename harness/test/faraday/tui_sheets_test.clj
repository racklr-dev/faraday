;; faraday.harness chat test: the remaining sheets + /compact.
;; Mounts chat-app headlessly (in-memory screen, injected :call/:exec/:summarize)
;; and drives it with tui/press!: /history lists past prompts and picks one back
;; into the composer; /models and /think (effort) select a provider/reasoning rung
;; and persist it to the temp settings file; /tree, /execs and /memory report
;; their honest state; /compact summarizes the prefix and drops the oldest turns.
;; Run: cd harness && jolt run test/faraday/tui_sheets_test.clj
(ns jolt.test.tui-sheets
  (:require [faraday.harness.chat :as chat]
            [clojure.data.json :as json]
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

(defn- mount! [screen settings-file & [extra]]
  (let [app (chat/chat-app (merge {:call (fn [_] "{:done \"ok\"}")
                                   :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                                   :wrap 40
                                   :settings-file settings-file}
                                  extra))
        root (w/node :window {})]
    (tui/attach! root screen)
    (ui/mount root :window [app])
    (tui/frame!)
    root))

(defn- tmp-file []
  (str (System/getProperty "java.io.tmpdir") "/faraday-tui-sheets-" (System/nanoTime) ".json"))

(defn- type-cmd! [s]
  (doseq [c (map int s)] (tui/press! c) (tui/frame!))
  (tui/press! 10)
  (tui/frame!))

(defn- type-text! [s]
  (doseq [c (map int s)] (tui/press! c) (tui/frame!)))

(defn- send-text! [s]
  (type-text! s)
  (tui/press! 10)
  (tui/frame!)
  (Thread/sleep 150)
  (tui/frame!))

;; -- /history opens, lists a sent prompt, and picks it back into the composer ---
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (send-text! "alpha")
  (type-cmd! "/history")
  (let [text (screen-text screen)]
    (check "/history opens the sheet" true (str/includes? text "history"))
    (check "/history lists the sent prompt" true (str/includes? text "alpha"))
    (check "/history rows carry a recency value" true (str/includes? text "1 ago")))
  ;; enter picks the newest prompt into the draft; a suffix lands with it
  (tui/press! 10)
  (tui/frame!)
  (type-text! " beta")
  (tui/press! 10)
  (tui/frame!)
  (Thread/sleep 150)
  (tui/frame!)
  (check "picked prompt plus suffix is sent" true (str/includes? (screen-text screen) "alpha beta")))

;; -- /models lists providers; selecting one persists the chat provider -----------
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-cmd! "/models")
  (let [text (screen-text screen)]
    (check "/models opens the sheet" true (str/includes? text "models"))
    (check "/models shows the default provider" true (str/includes? text "deepseek"))
    (check "/models shows the strong provider" true (str/includes? text "deepseek-pro")))
  ;; filter to the deepseek-pro row and activate it
  (doseq [c (map int "pro")] (tui/press! c) (tui/frame!))
  (tui/press! 10)
  (tui/frame!)
  (let [m (json/read-str (slurp tmp) :key-fn keyword)]
    (check "provider switch persisted" "deepseek-pro" (get-in m [:chat :provider]))))

;; -- /think lists reasoning rungs; selecting one persists chat.reasoning --------
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-cmd! "/think")
  (let [text (screen-text screen)]
    (check "/think opens the sheet" true (str/includes? text "effort"))
    (check "/think shows the max rung" true (str/includes? text "max")))
  (doseq [c (map int "max")] (tui/press! c) (tui/frame!))
  (tui/press! 10)
  (tui/frame!)
  (let [m (json/read-str (slurp tmp) :key-fn keyword)]
    (check "reasoning rung persisted" "max" (get-in m [:chat :reasoning]))))

;; -- /tree and /execs report their honest empty state -----------------------------
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-cmd! "/tree")
  (check "/tree reports no fanout" true (str/includes? (screen-text screen) "does not fan out"))
  (tui/press! {:type :escape})
  (tui/frame!)
  (type-cmd! "/execs")
  (check "/execs reports no running execs" true (str/includes? (screen-text screen) "no running execs")))

;; -- /memory reports the no-facts state when there is no session -------------------
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-cmd! "/memory")
  (check "/memory opens" true (str/includes? (screen-text screen) "memory"))
  (check "/memory reports no salient facts" true (str/includes? (screen-text screen) "no salient facts")))

;; -- /compact summarizes the prefix and drops the oldest turns --------------------
(let [tmp (tmp-file)
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp {:summarize (fn [_] "compact summary")})]
  (send-text! "a1")
  (send-text! "a2")
  (send-text! "a3")
  (type-cmd! "/compact")
  (Thread/sleep 200)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "compact adds the summary" true (str/includes? text "compact summary"))
    (check "compact keeps the recent tail" true (str/includes? text "a3"))
    (check "compact drops the oldest turn" false (str/includes? text "a1"))))

(println "ALL TUI SHEETS TESTS PASSED")

(shutdown-agents)
