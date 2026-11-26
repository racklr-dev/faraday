;; faraday-harness cells + loop-as-data test: the nodecode-style standing
;; context cells and the samizdat-style editable loop manifest. Hermetic: temp
;; dir, no network, no KVM, no model.
;; Run: cd harness && jolt run test/faraday/cells_loop_test.clj
(ns jolt.test.cells-loop
  (:require [faraday.harness.cells :as cells]
            [faraday.harness.loop :as loop]
            [faraday.harness.chat :as chat]
            [faraday.harness.tools :as tools]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(def root (str (System/getProperty "java.io.tmpdir") "/faraday-cells-loop-" (System/nanoTime)))
(.mkdirs (java.io.File. root))

;; -- cells -------------------------------------------------------------------
(check "cells start empty" [] (cells/read-cells root))

(let [c1 (cells/add-cell! root "jolt microvm egress" "libkrun has no outbound backend")
      c2 (cells/add-cell! root "operator prefers scripts" "write runnable scripts, not inline commands")]
  (check "add-cell assigns seq 1" 1 (:seq c1))
  (check "add-cell assigns seq 2" 2 (:seq c2))
  (check "read-cells persists in order" [1 2] (mapv :seq (cells/read-cells root)))
  (check "cells-lines renders standing notes"
         ["[1] jolt microvm egress" "[2] operator prefers scripts"]
         (cells/cells-lines (cells/read-cells root)))
  (check "show-cell returns full body" "libkrun has no outbound backend" (cells/show-cell (cells/read-cells root) 1)))

(let [r (cells/drop-cells! root [1])]
  (check "drop-cells removes seq 1" [2] (mapv :seq r)))

(let [r (cells/keep-cells! root [2])]
  (check "keep-cells keeps only seq 2" [2] (mapv :seq r)))

;; tool-shaped fns return the run_shell result shape
(let [r (cells/add-tool root {:note "keep" :body "body"})]
  (check "cell_add tool ok" :ok (:status r))
  (check "cell_add tool names seq" true (str/includes? (:stdout r) "3")))
(check-true "cells tool lists the note" (str/includes? (:stdout (cells/list-tool root {})) "keep"))
(check-true "cell_show tool returns body" (str/includes? (:stdout (cells/show-tool root {:id 3})) "body"))

;; -- loop-as-data -------------------------------------------------------------
(check "default loop has turn-budget 96" 96 (:turn-budget (loop/read-loop root)))
(check-true "default loop has working rules" (pos? (count (:rules (loop/read-loop root)))))
(check-true "loop-lines renders each rule" (str/starts-with? (first (loop/loop-lines (loop/read-loop root))) "- "))

(let [r (loop/set-tool root {:rules ["always verify with one command"] :turn-budget 40})]
  (check "loop_set ok" :ok (:status r)))
(let [m (loop/read-loop root)]
  (check "loop_set rewrites rules" ["always verify with one command"] (:rules m))
  (check "loop_set rewrites turn-budget" 40 (:turn-budget m)))

;; a corrupt loop file degrades to the default rather than breaking the chat
(spit (loop/loop-file root) "not a map")
(check "corrupt loop degrades to default" 96 (:turn-budget (loop/read-loop root)))

;; -- system prompt wiring -----------------------------------------------------
(let [s (chat/system-with-context "base" ["ctx"] nil ["[1] a cell"] ["- a rule"])]
  (check-true "system-with-context injects cells" (str/includes? s "Context cells"))
  (check-true "system-with-context injects loop rules" (str/includes? s "Loop rules"))
  (check-true "system-with-context keeps context" (str/includes? s "Context:\nctx")))

(check-true "agentic-system documents persistence" (str/includes? chat/agentic-system "You persist across sessions"))
(check-true "agentic-system points at list_tools" (str/includes? chat/agentic-system "list_tools"))

;; -- list_tools ----------------------------------------------------------------
(let [r (tools/list-tools)]
  (check "list_tools ok" :ok (:status r))
  (check-true "list_tools lists cells" (str/includes? (:stdout r) "cells"))
  (check-true "list_tools lists loop_get" (str/includes? (:stdout r) "loop_get")))

(println "ALL CELLS/LOOP TESTS PASSED")
