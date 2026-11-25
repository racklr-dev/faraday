;; faraday-harness TUI scroll-follow test: the transcript pins to the bottom as
;; a long answer arrives, detaches on scroll-up, pgdn/End jump back to the
;; bottom and re-follow, Home jumps to the top while unlocked, and a round that
;; finishes re-pins the bottom even if the operator scrolled up while it was
;; working. Headless, no network, no terminal.
(ns jolt.test.tui-scroll-follow
  (:require [faraday.harness.chat :as chat]
            [glimmer.core :as ui]
            [glimmer.backend :as b]
            [glimmer-tui.core :as tui]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w]
            [glimmer-tui.widgets.scroll :as scroll]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(defn- find-node [tag w]
  (when w
    (if (= tag (:tag w))
      w
      (some #(find-node tag %) (:children w)))))

(defn- scroll-node [tree]
  (find-node :scroll tree))

(defn- bottom? [tree]
  (when-let [n (scroll-node tree)]
    (let [off (get-in n [:offset :y] 0)
          content-h (get-in n [:content :h] 0)
          view-h (get-in n [:viewport :h] 0)]
      (>= off (- content-h view-h)))))

(defn- top? [tree]
  (when-let [n (scroll-node tree)]
    (<= (get-in n [:offset :y] 0) 0)))

(defn- loop-step []
  (tui/drain!)
  (tui/pump-timers!)
  (tui/frame!))

(defn- mount! [screen fake-call]
  (let [app (chat/chat-app {:call fake-call
                            :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                            :wrap 60})
        root (w/node :window {})]
    (tui/attach! root screen)
    (ui/mount root :window [app])
    (reset! @#'glimmer.core/live-root {:inst root})
    (reset! @#'glimmer.backend/loop-running? true) ; on-gui schedules, like the real loop
    (tui/frame!)
    root))

(defn- send-hi! []
  (tui/press! 104) ; h
  (tui/press! 105) ; i
  (tui/press! 10)) ; Enter

;; -- a long answer pins to the bottom -----------------------------------------
(let [long-answer (str/join "\n" (map #(str "line " %) (range 1 41)))
      fake-call (fn [_] (pr-str {:done long-answer}))
      root (mount! (scr/buffer-screen 80 20) fake-call)]
  (send-hi!)
  (Thread/sleep 150)
  (loop-step) ; fire every! -> schedule scroll; drain; repaint
  (check "long answer leaves the transcript pinned to the bottom"
         true (bottom? (tui/tree))))

;; -- scrolling up detaches; pgdn jumps back and re-follows --------------------
(let [long-answer (str/join "\n" (map #(str "line " %) (range 1 41)))
      fake-call (fn [_] (pr-str {:done long-answer}))
      root (mount! (scr/buffer-screen 80 20) fake-call)]
  (send-hi!)
  (Thread/sleep 150)
  (loop-step)
  (check "long answer pinned to the bottom before detach"
         true (bottom? (tui/tree)))
  (tui/press! {:type :up})
  (tui/frame!)
  (check "up arrow detaches (scrolls off the bottom)" false (bottom? (tui/tree)))
  (tui/press! {:type :page-down})
  (tui/frame!)
  (check "pgdn jumps straight back to the bottom" true (bottom? (tui/tree))))

;; -- a round that finishes re-pins the bottom after a mid-round scroll-up -----
(let [long-answer (str/join "\n" (map #(str "line " %) (range 1 41)))
      calls (atom 0)
      fake-call (fn [_]
                  (swap! calls inc)
                  (if (= 1 @calls)
                    (pr-str {:done long-answer})
                    (do (Thread/sleep 500) (pr-str {:done "second short answer"}))))
      root (mount! (scr/buffer-screen 80 20) fake-call)]
  ;; round 1 fills the transcript so there is something to scroll up into
  (send-hi!)
  (Thread/sleep 150)
  (loop-step)
  (check "round 1 pinned to the bottom" true (bottom? (tui/tree)))
  ;; round 2 is slow; scroll up while it works
  (send-hi!)
  (Thread/sleep 150)            ; every! is due; the turn is still busy
  (loop-step)                   ; tick while busy: records was-busy? true
  (tui/press! {:type :up})      ; operator reads earlier output
  (tui/frame!)
  (check "operator scrolled up mid-round" false (bottom? (tui/tree)))
  (Thread/sleep 600)            ; the turn finishes now
  (loop-step)                   ; completion tick: re-follow + scroll
  (check "a finished round re-pins the transcript to the bottom"
         true (bottom? (tui/tree))))

;; -- Home/End navigate the transcript while unlocked --------------------------
(let [long-answer (str/join "\n" (map #(str "line " %) (range 1 41)))
      fake-call (fn [_] (pr-str {:done long-answer}))
      root (mount! (scr/buffer-screen 80 20) fake-call)]
  (send-hi!)
  (Thread/sleep 150)
  (loop-step)
  (tui/drain!) ; clear the every! timer's queued scroll-to-bottom write
  (check "pinned before Home/End" true (bottom? (tui/tree)))
  ;; following (locked): Home/End belong to the composer, not the transcript
  (tui/press! {:type :home})
  (tui/frame!)
  (check "Home while following stays pinned (composer keeps it)" true (bottom? (tui/tree)))
  (tui/press! {:type :end})
  (tui/frame!)
  (check "End while following stays pinned (composer keeps it)" true (bottom? (tui/tree)))
  ;; scroll up to unlock, then drain so the re-render hands Home/End to the
  ;; transcript (the entry's :keys override applies)
  (tui/press! {:type :up})
  (tui/drain!)
  (tui/frame!)
  (check "scrolled up (unlocked)" false (bottom? (tui/tree)))
  ;; Home jumps to the top while unlocked
  (tui/press! {:type :home})
  (tui/frame!)
  (check "Home jumps to the top while unlocked" true (top? (tui/tree)))
  ;; End jumps back to the bottom and re-follows
  (tui/press! {:type :end})
  (tui/frame!)
  (check "End jumps to the bottom and re-follows" true (bottom? (tui/tree))))

(println "ALL TUI SCROLL-FOLLOW TESTS PASSED")
(shutdown-agents)
