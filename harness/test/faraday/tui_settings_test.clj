;; faraday.harness chat test: the /settings and /config dialogs.
;; Mounts chat-app headlessly with a hermetic :settings-file and drives it with
;; tui/press!: /settings opens the editable sheet, a filter narrows to one row,
;; space toggles a boolean (persisted to the temp settings file), enter opens the
;; value editor, esc cancels back to the list, and /config browses read-only.
;; Run: cd harness && jolt run test/faraday/tui_settings_test.clj
(ns jolt.test.tui-settings
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

(defn- mount! [screen settings-file]
  (let [app (chat/chat-app {:call (fn [_] "{:done \"ok\"}")
                            :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                            :wrap 40
                            :settings-file settings-file})
        root (w/node :window {})]
    (tui/attach! root screen)
    (ui/mount root :window [app])
    (tui/frame!)
    root))

(defn- tmp-file []
  (str (System/getProperty "java.io.tmpdir") "/faraday-tui-settings-" (System/nanoTime) ".json"))

(defn- type-slash-command! [codes]
  (doseq [code codes] (tui/press! code) (tui/frame!))
  (tui/press! 10)
  (tui/frame!))

;; -- /settings opens and lists sections ----------------------------------------
(let [tmp (tmp-file)
      _ (spit tmp "{\"chat\":{\"quiet\":false,\"wrap\":80}}")
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-slash-command! [47 115 101 116 116 105 110 103 115])  ; /settings
  (let [text (screen-text screen)]
    (check "/settings opens the dialog" true (str/includes? text "settings"))
    (check "settings footer mentions toggle/edit" true (str/includes? text "toggle/edit"))
    (check "shows the chat section" true (str/includes? text "chat"))))

;; -- filter + space toggles and persists ---------------------------------------
(let [tmp (tmp-file)
      _ (spit tmp "{\"chat\":{\"quiet\":false}}")
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-slash-command! [47 115 101 116 116 105 110 103 115])
  ;; narrow the list to the quiet toggle
  (doseq [code [113 117 105 101 116]] (tui/press! code) (tui/frame!))  ; quiet
  (check "filter shows the quiet toggle unchecked"
          true (str/includes? (screen-text screen) "[ ] quiet"))
  ;; space flips it on, in place, and writes back to the settings file
  (tui/press! 32)
  (tui/frame!)
  (check "space flips the toggle on screen" true (str/includes? (screen-text screen) "[x] quiet"))
  (let [m (json/read-str (slurp tmp) :key-fn keyword)]
    (check "toggle persisted quiet=true" true (get-in m [:chat :quiet]))))

;; -- enter opens the value editor; esc cancels ----------------------------------
(let [tmp (tmp-file)
      _ (spit tmp "{\"chat\":{\"wrap\":80}}")
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-slash-command! [47 115 101 116 116 105 110 103 115])
  (doseq [code [119 114 97 112]] (tui/press! code) (tui/frame!))  ; wrap
  (check "filter shows the wrap value row" true (str/includes? (screen-text screen) "wrap"))
  (tui/press! 10)
  (tui/frame!)
  (check "enter opens the value editor" true (str/includes? (screen-text screen) "enter save"))
  (tui/press! {:type :escape})
  (tui/frame!)
  (check "esc returns to the settings list" true (str/includes? (screen-text screen) "toggle/edit")))

;; -- /config browses read-only ---------------------------------------------------
(let [tmp (tmp-file)
      _ (spit tmp "{\"chat\":{\"wrap\":80}}")
      screen (scr/buffer-screen 72 30)
      root (mount! screen tmp)]
  (type-slash-command! [47 99 111 110 102 105 103])  ; /config
  (let [text (screen-text screen)]
    (check "/config opens read-only" true (str/includes? text "read-only")))
  (tui/press! 10)
  (tui/frame!)
  (check "enter does not open an editor in /config" false (str/includes? (screen-text screen) "enter save")))

;; -- project-file precedence: edits land in the project settings file ----------
(let [root (str (System/getProperty "java.io.tmpdir") "/faraday-tui-proj-" (System/nanoTime))
      _ (.mkdirs (java.io.File. (str root "/.faraday")))
      _ (spit (str root "/.faraday/harness.db") "x")
      proj (str root "/.faraday/settings.json")
      _ (spit proj "{\"chat\":{\"quiet\":false}}")
      user (tmp-file)
      _ (spit user "{\"chat\":{\"quiet\":true}}")
      screen (scr/buffer-screen 72 30)
      _ (let [app (chat/chat-app {:call (fn [_] "{:done \"ok\"}")
                                   :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                                   :wrap 40
                                   :settings-file user
                                   :proj-root root})
              rt (w/node :window {})]
          (tui/attach! rt screen)
          (ui/mount rt :window [app])
          (tui/frame!))
      _ (type-slash-command! [47 115 101 116 116 105 110 103 115])
      _ (doseq [code [113 117 105 101 116]] (tui/press! code) (tui/frame!))]
  (check "project mode shows resolved (project wins) value"
          true (str/includes? (screen-text screen) "[ ] quiet"))
  (tui/press! 32)
  (tui/frame!)
  (check "space flips in project mode" true (str/includes? (screen-text screen) "[x] quiet"))
  (let [pm (json/read-str (slurp proj) :key-fn keyword)
        um (json/read-str (slurp user) :key-fn keyword)]
    (check "edit landed in the project settings file" true (get-in pm [:chat :quiet]))
    (check "user settings file was not rewritten" true (get-in um [:chat :quiet]))))

(println "ALL TUI SETTINGS TESTS PASSED")
