;; faraday.harness.dialog test: the pure navigation math and row formatting.
;; Run: cd harness && jolt run test/faraday/dialog_test.clj
(ns jolt.test.dialog
  (:require [clojure.string :as str]
            [faraday.harness.dialog :as dlg]
            [glimmer-tui.keys :as k]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- filter -------------------------------------------------------------------
(let [items [{:label "model" :value "deepseek-chat"}
             {:label "api-key" :value "sk-hidden" :desc "the key"}
             {:label "temperature" :value "0.4"}]]
  (check "blank filter returns all" 3 (count (dlg/filter-items items "")))
  (check "filter matches label" 1 (count (dlg/filter-items items "model")))
  (check "filter matches value" 1 (count (dlg/filter-items items "hidden")))
  (check "filter matches desc" 1 (count (dlg/filter-items items "key")))
  (check "filter is case-insensitive" 1 (count (dlg/filter-items items "DEEP")))
  (check "no match is empty" 0 (count (dlg/filter-items items "zzz"))))

;; -- step ---------------------------------------------------------------------
(let [items (vec (range 10))]
  (check "down advances" 1 (dlg/step items 0 :down))
  (check "down clamps at end" 9 (dlg/step items 9 :down))
  (check "up clamps at 0" 0 (dlg/step items 0 :up))
  (check "page-down jumps 8" 8 (dlg/step items 0 :page-down))
  (check "page-up clamps" 0 (dlg/step items 3 :page-up))
  (check "home is 0" 0 (dlg/step items 7 :home))
  (check "end is last" 9 (dlg/step items 2 :end))
  (check "empty list steps to 0" 0 (dlg/step [] 0 :down)))

;; -- toggle / fold / tab ------------------------------------------------------
(let [items [{:label "a" :kind :toggle :checked? false}
             {:label "b" :kind :row}]]
  (check "toggle flips on" true (get-in (dlg/toggle-at items 0) [0 :checked?]))
  (check "toggle flips off" false (get-in (dlg/toggle-at (dlg/toggle-at items 0) 0) [0 :checked?]))
  (check "toggle out of range is a no-op" items (dlg/toggle-at items 9)))

(let [items [{:label "p" :children [{:label "c"}] :expanded? false}]]
  (check "fold right unfolds" true (get-in (dlg/fold-at items 0 :right) [0 :expanded?]))
  (check "fold left folds" false (get-in (dlg/fold-at items 0 :left) [0 :expanded?])))

(check "tab advances" 1 (dlg/step-tab 3 0 :tab))
(check "tab clamps" 2 (dlg/step-tab 3 2 :tab))
(check "shift-tab clamps at 0" 0 (dlg/step-tab 3 0 :shift-tab))

;; -- row formatting -----------------------------------------------------------
(let [row (dlg/format-row {:label "model" :value "deepseek"} 10)]
  (check "format-row pads name to a column" "model       deepseek" row))
(check "format-row drops value when absent" "model"
       (dlg/format-row {:label "model"} 10))
(check "toggle row shows an unchecked box" "[ ] quiet"
       (dlg/format-row {:label "quiet" :kind :toggle :checked? false} 0))
(check "toggle row shows a checked box" "[x] quiet"
       (dlg/format-row {:label "quiet" :kind :toggle :checked? true} 0))
(check "tree row shows a fold marker" "▸ parent"
       (dlg/format-row {:label "parent" :children [{}] :expanded? false} 0))
(check "tree depth indents two spaces" "  child"
       (dlg/format-row {:label "child" :depth 1} 0))
(check "desc is appended after the value"
       (str (dlg/format-row {:label "model" :value "deepseek"} 10) "  the model")
       (dlg/format-row {:label "model" :value "deepseek" :desc "the model"} 10))

;; -- key mapping --------------------------------------------------------------
(check "up maps to a step" [:step :up] (dlg/key-action (k/decode 259) false))
(check "pgdn maps to page-down" [:step :page-down] (dlg/key-action (k/decode 338) false))
(check "space toggles" [:toggle] (dlg/key-action (k/decode 32) false))
(check "right folds" [:fold :right] (dlg/key-action (k/decode 261) false))
(check "enter activates" [:activate] (dlg/key-action (k/decode 10) false))
(check "tab with no tabs declines" nil (dlg/key-action (k/decode 9) false))
(check "tab with tabs switches" [:tab :tab] (dlg/key-action (k/decode 9) true))
(check "a letter filters" [:filter-char \h] (dlg/key-action (k/decode 104) false))
(check "ctrl+y is declined" nil (dlg/key-action (k/decode 25) false))

;; -- apply-action -------------------------------------------------------------
(let [dlg {:items [{:label "a"} {:label "b"} {:label "c"}] :selected 1 :filter ""}]
  (check "step down moves selection" 2 (:selected (dlg/apply-action dlg [:step :down])))
  (check "filter char appends and resets" ["x" 0]
         [(:filter (dlg/apply-action dlg [:filter-char \x]))
          (:selected (dlg/apply-action dlg [:filter-char \x]))])
  (check "filter backspace drops last" "a"
         (:filter (dlg/apply-action (assoc dlg :filter "ab") [:filter-backspace])))
  (check "activate leaves the model unchanged" dlg (dlg/apply-action dlg [:activate])))

;; -- wrapped rows -------------------------------------------------------------
(let [sp15 (apply str (repeat 15 " "))
      rows (dlg/format-rows
            [{:label "model" :value "deepseek-chat"}
             {:label "system-prompt"
              :value "You are faraday, a terminal work agent that carries out orders end to end with tools, then answers with what was done and verified."}
             {:label "note"
              :desc "a very long description that should wrap onto a second line and then be clipped with an ellipsis because it keeps going past the available column width of the dialog"}]
            64)
      lines (fn [r] (vec (str/split (:label r) #"\n" -1)))]
  (check "short value stays on one line" 1 (count (lines (first rows))))
  (check "long value wraps to two lines" 2 (count (lines (second rows))))
  (check "long desc wraps and clips to two lines" 2 (count (lines (last rows))))
  (check "clipped value ends with an ellipsis" true (str/ends-with? (:label (second rows)) "…"))
  (check "clipped desc ends with an ellipsis" true (str/ends-with? (:label (last rows)) "…"))
  (check "continuation lines hang-indent under the value column"
         true
         (str/starts-with? (second (lines (second rows))) sp15))
  (check "wrapped rows stay inside the dialog content width"
         true
         (every? #(<= (count %) 56) (mapcat lines rows))))

(println "ALL DIALOG TESTS PASSED")
