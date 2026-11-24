;; faraday console terminal tabs: the direct glimmer-tui component. Hermetic —
;; renders the component and drives the :view ratom the way the tab buttons do.
(ns jolt.test.console-tabs
  (:require [faraday.console.views.glimmer :as gv]
            [faraday.console.board :as board]
            [glimmer.ratom :as r]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(defn buttons [hiccup]
  (filter #(and (vector? %) (= :button (first %)))
          (tree-seq coll? seq hiccup)))

(let [b @board/board
      view (r/atom "overview")
      h (gv/app b view)]
  (check-true "overview shows plane stats" (str/includes? (pr-str h) "plane"))
  (check "overview hides the runs placeholder" false (str/includes? (pr-str h) "(no runs yet)"))
  (reset! view "runs")
  (let [h2 (gv/app b view)]
    (check-true "runs shows the runs placeholder" (str/includes? (pr-str h2) "(no runs yet)"))
    (check "runs hides plane stats" false (str/includes? (pr-str h2) "granted"))))

(let [view (r/atom "overview")
      h (gv/app @board/board view)
      runs-btn (first (filter #(= "Runs" (get-in % [1 :label])) (buttons h)))]
  (check-true "runs tab button exists" (some? runs-btn))
  ((get-in runs-btn [1 :on-click]))
  (check "clicking Runs sets the view" "runs" @view))

(println "ALL CONSOLE TABS TESTS PASSED")
