;; faraday.harness chat test: /fork, /run and /spawn wired into the shell.
;; /fork mints a new session thread at the current head and switches the live
;; thread (proved by the old thread staying at its pre-fork head). /run and
;; /spawn dispatch synchronously for the usage/error paths, and their end-to-end
;; runner (faraday.harness.run/run-manifest!) is asserted directly here — the
;; same seam the async command path calls.
;; Run: cd harness && jolt run test/faraday/tui_run_spawn_fork_test.clj
(ns jolt.test.tui-run-spawn-fork
  (:require [faraday.harness :as h]
            [faraday.harness.chat :as chat]
            [faraday.harness.run :as run]
            [faraday.harness.session :as session]
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

(defn- type-line! [root s]
  (doseq [c (map int s)] (tui/press! c) (tui/frame!))
  (tui/press! 10)
  (tui/frame!))

(defn- tmp-dir []
  (str (.toAbsolutePath (java.nio.file.Files/createTempDirectory "frun" (make-array java.nio.file.attribute.FileAttribute 0)))))

(def manifest
  {:v 1 :loop-id "m" :name "m"
   :agent {:kind "script"
           :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "hi"]} :display-as "echo"}}
                    {:done {:result :ok}}]}})

;; -- /fork: mint a new thread at the current head and switch to it -------------
(let [dir (tmp-dir)
      stores (session/open dir)
      screen (scr/buffer-screen 80 30)
      root (mount! screen {:session stores :thread-id "fork-a" :cwd dir :proj-root dir})
      wait-turn! (fn [] (Thread/sleep 200) (tui/drain!) (tui/frame!))]
  (type-line! root "one")
  (wait-turn!)
  (check "fork pre-turn landed" true (str/includes? (screen-text screen) "one"))
  (type-line! root "/fork")
  (wait-turn!)
  (let [text (screen-text screen)]
    (check "/fork flashes the new thread id" true (str/includes? text "forked"))
    (check "/fork consumes the composer" "" (get-in @(find-widget :entry root) [:props :text])))
  (type-line! root "two")
  (wait-turn!)
  (check "old thread keeps only its pre-fork head"
         2
         (count (session/resume stores "fork-a"))))

;; -- /run + /spawn synchronous dispatch (usage/error paths) --------------------
(let [screen (scr/buffer-screen 80 30)
      root (mount! screen {:cwd "/tmp" :proj-root "/tmp"})]
  (type-line! root "/run")
  (check "/run blank flashes usage" true (str/includes? (screen-text screen) "usage: /run"))
  (check "/run blank consumes the composer" "" (get-in @(find-widget :entry root) [:props :text]))
  (type-line! root "/run no-such-manifest.edn")
  (check "/run missing manifest reports it" true (str/includes? (screen-text screen) "no task or manifest"))
  (type-line! root "/spawn no-such.edn")
  (check "/spawn missing manifest reports usage" true (str/includes? (screen-text screen) "usage: /spawn"))
  (type-line! root "/spawn no-such.edn 3")
  (check "/spawn missing manifest still reports usage" true (str/includes? (screen-text screen) "usage: /spawn")))

;; -- the end-to-end runner the async /run + /spawn paths call ------------------
(let [dir (tmp-dir)]
  (let [r (run/run-manifest! dir manifest)]
    (check "run-manifest! n=1 launches one loop" 1 (:n r))
    (check "run-manifest! n=1 reaches done" :done (get-in r [:outcomes 0 :outcome :status])))
  (let [r (run/run-manifest! dir manifest :n 3)]
    (check "run-manifest! n=3 launches three loops" 3 (:n r))
    (check "run-manifest! n=3 all reach done" [:done :done :done]
           (mapv (comp :status :outcome) (:outcomes r)))))

(println "ALL RUN/SPAWN/FORK TESTS PASSED")

(shutdown-agents)
