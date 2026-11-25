;; faraday.harness chat test: the / command picker wired into the composer.
;; Mounts chat-app headlessly (in-memory screen, injected :call/:exec) and drives
;; it with tui/press!: typing / opens the dropdown, typing narrows it, tab
;; completes the selection, enter runs a command (clear wired first), and the
;; composer consumes the command exactly as nodecode does.
;; Run: cd harness && jolt run test/faraday/tui_commands_test.clj
(ns jolt.test.tui-commands
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

(defn- find-widget [tag w]
  (when w
    (if (= tag (:tag @w))
      w
      (some #(find-widget tag %) (:children @w)))))

(defn- screen-text [screen] (apply str (scr/lines screen)))

(defn- mount! [screen & [extra]]
  (let [app (chat/chat-app (merge {:call (fn [_] "{:done \"ok\"}")
                                  :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                                  :wrap 40}
                                 extra))
        root (w/node :window {})]
    (tui/attach! root screen)
    (ui/mount root :window [app])
    (tui/frame!)
    root))

;; -- opening, narrowing, completing --------------------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  (tui/press! 47)   ; /
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "typing / opens the command dropdown" true (str/includes? text "/board"))
    (check "dropdown shows the picker footer" true (str/includes? text "choose")))
  (tui/press! 104)  ; h -> /h
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "typing narrows the list" true (str/includes? text "/history"))
    (check "dropdown stays open while completing" true (str/includes? text "command")))
  (tui/press! {:type :tab})
  (tui/frame!)
  (check "tab completes the selected command with a trailing space"
          "/help "
          (get-in @(find-widget :entry root) [:props :text]))
  (check "dropdown closes after completion"
          false
          (str/includes? (screen-text screen) "choose")))

;; -- enter runs a command and consumes the composer ---------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  ;; a real message first, so /clear has something to clear
  (tui/press! 104) (tui/press! 105) (tui/press! 10)
  (Thread/sleep 150) (tui/drain!) (tui/frame!)
  ;; type /clear and run it (one frame per key, matching the real event loop,
  ;; which repaints between keystrokes)
  (doseq [code [47 99 108 101 97 114]] (tui/press! code) (tui/frame!))
  (tui/press! 10)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "clear toast appears" true (str/includes? text "session cleared"))
    (check "clear consumes the composer" "" (get-in @(find-widget :entry root) [:props :text]))))

;; -- /export saves the transcript as Markdown -------------------------------
(let [target "/tmp/faraday-e.md"
      _ (.delete (java.io.File. target))
      screen (scr/buffer-screen 60 30)
      root (mount! screen {:thread-id "e" :cwd "/tmp"})]
  (doseq [code [47 101 120 112 111 114 116]] (tui/press! code) (tui/frame!))
  (tui/press! 10)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "/export toast shows the saved path"
           true
           (str/includes? text "saved /tmp/faraday-e.md"))
    (check "/export consumes the composer"
           ""
           (get-in @(find-widget :entry root) [:props :text]))
    (check "/export writes the markdown file"
           true
           (.isFile (java.io.File. target))))
  (.delete (java.io.File. target)))

;; -- esc closes the dropdown without acting -----------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  (tui/press! 47)   ; /
  (tui/frame!)
  (check "dropdown open before esc" true (str/includes? (screen-text screen) "choose"))
  (tui/press! {:type :escape})
  (tui/frame!)
  (check "esc closes the dropdown" false (str/includes? (screen-text screen) "choose"))
  (check "esc leaves the draft for editing" "/" (get-in @(find-widget :entry root) [:props :text])))

;; -- /board + /task bridge the harness taskboard ------------------------------
(let [dir (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "fboard" (make-array java.nio.file.attribute.FileAttribute 0))))
      hd (h/new-harness {:path (str dir "/.faraday/harness.db")})
      proj (h/project-add! hd {:root-path dir :name "t" :default-manifest "default"})
      _ (h/scope-add! hd {:project-id (:id proj) :rel-path "" :is-root true :meta {}})
      screen (scr/buffer-screen 80 30)
      root (mount! screen {:harness (delay hd) :proj-root dir :board proj})
      type-line! (fn [s]
                   (doseq [c (map int s)] (tui/press! c) (tui/frame!))
                   (tui/press! 10)
                   (tui/frame!))
      close-dialog! (fn [] (tui/press! {:type :escape}) (tui/frame!))]
  (type-line! "/task add fix the bug --priority high")
  (check "/task add flashes the new id" true (str/includes? (screen-text screen) "added faraday-"))
  (check "/task add consumes the composer" "" (get-in @(find-widget :entry root) [:props :text]))
  (let [t (first (h/task-list hd (:id proj)))]
    (check "/task add persisted title+priority" ["fix the bug" "high"] [(:title t) (:priority t)])
    (type-line! (str "/task show " (:id t)))
    (check "/task show opens the detail sheet" true (str/includes? (screen-text screen) "fix the bug"))
    (close-dialog!)
    (type-line! "/board")
    (check "/board opens the kanban sheet" true (str/includes? (screen-text screen) "fix the bug"))
    (close-dialog!)
    (type-line! "/task list --status open")
    (check "/task list opens the filtered sheet" true (str/includes? (screen-text screen) "fix the bug"))
    (close-dialog!)
    (type-line! (str "/task close " (:id t)))
    (check "/task close moves it to done" true (str/includes? (screen-text screen) "→ done"))
    (check "/task close persisted the status" "done" (:status (h/task hd (:id t))))))

;; -- /undo + /redo rewind the last turn ---------------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen {:call (fn [_] "{:done \"beta answer\"}")
                           :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})})
      type-line! (fn [s]
                   (doseq [c (map int s)] (tui/press! c) (tui/frame!))
                   (tui/press! 10)
                   (tui/frame!))]
  (type-line! "alpha task")
  (Thread/sleep 150) (tui/drain!) (tui/frame!)
  (check "turn lands: user + assistant answer"
         true
         (and (str/includes? (screen-text screen) "alpha task")
              (str/includes? (screen-text screen) "beta answer")))
  (type-line! "/undo")
  (let [text (screen-text screen)]
    (check "/undo flashes the rewound count" true (str/includes? text "rewound 2 entries"))
    (check "/undo removes the last turn"
           false
           (or (str/includes? text "alpha task") (str/includes? text "beta answer"))))
  (type-line! "/undo")
  (check "/undo with nothing left reports it" true (str/includes? (screen-text screen) "nothing to undo"))
  (type-line! "/redo")
  (let [text (screen-text screen)]
    (check "/redo flashes the return" true (str/includes? text "returned 2 entries"))
    (check "/redo restores the rewound turn"
           true
           (and (str/includes? text "alpha task") (str/includes? text "beta answer"))))
  (type-line! "/redo")
  (check "/redo with nothing rewound reports it" true (str/includes? (screen-text screen) "nothing to redo")))

;; -- /cost flashes session token usage ----------------------------------------
(let [screen (scr/buffer-screen 60 30)
      root (mount! screen)]
  (doseq [code [47 99 111 115 116]] (tui/press! code) (tui/frame!))
  (tui/press! 10)
  (tui/frame!)
  (let [text (screen-text screen)]
    (check "/cost shows zero usage with the injected :call seam"
           true
           (str/includes? text "↑0 ↓0 · 0 calls"))
    (check "/cost consumes the composer"
           ""
           (get-in @(find-widget :entry root) [:props :text]))))

(println "ALL TUI COMMANDS TESTS PASSED")

(shutdown-agents)
