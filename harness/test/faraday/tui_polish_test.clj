;; faraday-harness TUI polish test: the nodecode-style chat surface (role
;; colours, markdown headings, fenced-code slabs, dark composer, status line)
;; and the transcript renderer. Hermetic, no network.
(ns jolt.test.tui-polish
  (:require [faraday.harness.chat :as chat]
            [faraday.harness.console :as c]
            [faraday.themes :as themes]
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

(binding [themes/*settings-file* (doto (java.io.File/createTempFile "faraday-themes" ".json") (spit "{}"))
          themes/*themes-dir* (doto (java.io.File/createTempFile "faraday-themes-dir" ".d") (.mkdirs))
          themes/*system-appearance* :dark]
;; -- the transcript renderer, pure -------------------------------------------
(let [segs (chat/markdown-segments "# Title\nsome prose\n```\n(defn x [] 1)\n```\n- item")]
  (check-true "markdown groups a fenced code block"
              (some #(= :code (:t %)) segs))
  (check "markdown keeps code verbatim"
         ["(defn x [] 1)"]
         (:lines (first (filter #(= :code (:t %)) segs)))))

(let [es (chat/entry-segments {:role :assistant :text "# T\n```\n(defn x [] 1)\n```"} 40)
      code (first (filter #(= :code (:t %)) es))]
  (check-true "assistant entry emits a code segment" (some? code))
  (check "code segment hard-wraps verbatim" ["(defn x [] 1)"] (:lines code)))

(let [es (chat/entry-segments {:role :assistant :text "- alpha beta gamma delta"} 10)
      line-kinds (mapv :kind (filter #(= :line (:t %)) es))]
  (check "wrapped bullet keeps one marker" :bullet (first line-kinds))
  (check "wrapped bullet continuations are not bullets"
         [] (remove #{:bullet-cont} (rest line-kinds)))
  (check "wrapped bullet spans more than one line" true (> (count line-kinds) 1)))
(check "bullet continuation indents under the marker"
       "    " (#'chat/line-prefix :assistant :bullet-cont))

(let [es (chat/entry-segments {:role :assistant :text "1. alpha beta gamma delta"} 10)
      line-kinds (mapv :kind (filter #(= :line (:t %)) es))]
  (check "ordered list first line is numbered" :numbered (first line-kinds))
  (check "ordered list continuations are not numbered"
         [] (remove #{:numbered-cont} (rest line-kinds)))
  (check "ordered list wraps to more than one line" true (> (count line-kinds) 1)))
(check "numbered prefix shows the number"
       "  1. " (#'chat/line-prefix :assistant :numbered nil 1))
(check "numbered prefix shows wider numbers"
       "  10. " (#'chat/line-prefix :assistant :numbered nil 10))
(check "numbered continuation indents under the number"
       "     " (#'chat/line-prefix :assistant :numbered-cont nil 1))

(let [segs (chat/markdown-segments "**bold** and `code` and ~~gone~~")
      s (:spans (first segs))]
  (check-true "markdown carries inline spans" (boolean (seq s)))
  (check-true "markdown marks strong" (boolean (some #(contains? (:style %) :strong) s)))
  (check-true "markdown marks code" (boolean (some #(contains? (:style %) :code) s)))
  (check-true "markdown marks strike" (boolean (some #(contains? (:style %) :strike) s))))

(let [h (chat/segment-hiccup
                             {:t :line :role :assistant :kind :text
                              :text "bold"
                              :spans [{:text "bold" :style #{:strong}}]})]
  (check "strong span renders bold"
         {:color "#fca864" :bold true :label "bold"}
         (second h)))

(let [h (chat/segment-hiccup
                             {:t :line :role :assistant :kind :text
                              :text "docs"
                              :spans [{:text "docs" :style #{:link} :href "https://x"}]})]
  (check "link span renders accent + underline"
         {:color "#6ea8ff" :underline true :label "docs"}
         (second h)))

(let [h (chat/segment-hiccup
                             {:t :line :role :assistant :kind :text
                              :text "run bb"
                              :spans [{:text "run " :style nil}
                                      {:text "bb" :style #{:code}}]})]
  (check-true "multi-span line renders an hbox" (= :hbox (first h))))

(let [h (chat/segment-hiccup {:t :code :lines ["(defn x [] 1)"]})]
  (check "code renders as a slab box" :box (first h))
  (check "slab uses the nodecode slab colour" "#1b2125" (get-in h [1 :bg])))

(let [h (chat/segment-hiccup {:t :line :role :user :kind :text :text "hi"})]
  (check "user line uses nodecode orange, bold, prefix"
         {:color "#fb923c" :bold true :label "you> hi"}
         (second h)))

;; -- task lists, rules and tables ---------------------------------------------
(let [segs (chat/markdown-segments "- [x] done\n- [ ] todo")]
  (check "task list carries checkboxes" [true false] (mapv :checked? segs))
  (check "task list keeps task kinds" [:task :task] (mapv :kind segs)))

(check "horizontal rule is its own segment" true
       (boolean (some #(= :hr (:t %)) (chat/markdown-segments "a\n---\nb"))))

(let [h (chat/segment-hiccup {:t :table :rows [["a" "b"] ["1" "2"]] :aligns [:left :left] :width 40})]
  (check "table renders as a slab box" :box (first h)))

(let [h (chat/segment-hiccup {:t :line :role :assistant :kind :task :checked? false :text "todo"})]
  (check "task line renders an empty checkbox" "  ☐ todo" (:label (second h))))

;; -- the chat surface, mounted headless --------------------------------------
(let [chat-opts {:call (fn [_] "{:done \"ok\"}")
                 :exec (fn [_ _] {:status :ok :exit 0 :stdout ""})
                 :system "S" :max-turns 1}
      root (w/node :window {})
      screen (scr/buffer-screen 80 24)]
  (tui/attach! root screen)
  (ui/mount root :window [(c/app chat-opts)])
  (let [tree (tui/frame!)
        nodes (w/walk tree)
        text (apply str (scr/lines screen))]
    (check-true "chat renders the composer prompt" (str/includes? text "›"))
    (check-true "chat renders the status line" (str/includes? text "model"))
    ;; the composer entry is always focused; its pair is pre-inverted so the
    ;; focus reverse resolves back to light-on-dark rather than a white bar.
    (let [entry (first (filter #(= :entry (:tag %)) nodes))
          {:keys [x y]} (:rect entry)
          st (scr/style-at screen x y)]
      (check "composer entry pre-inverts fg" "#3b3937" (:fg st))
      (check "composer entry pre-inverts bg" "#e7e5e4" (:bg st))
      (check-true "composer entry paints reverse" (:reverse st)))))

;; -- headings render as a band ------------------------------------------------
(let [es (chat/entry-segments {:role :assistant :text "# Title"} 40)
      h (chat/segment-hiccup (first es))]
  (check "heading renders as a band box" :box (first h))
  (check "heading band uses the h1 shade" "#1d374b" (get-in h [1 :bg])))

(println "ALL TUI POLISH TESTS PASSED")
)
(shutdown-agents)