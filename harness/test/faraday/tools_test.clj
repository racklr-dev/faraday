;; faraday-harness tools test: the chat's non-shell tools (taskboard/scope/hook)
;; dispatch against a real harness DB. Hermetic: temp dir, no network, no KVM.
(ns jolt.test.tools
  (:require [faraday.harness.tools :as tools]
            [faraday.harness :as h]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(def root (str (System/getProperty "java.io.tmpdir") "/faraday-tools-" (System/nanoTime)))
(.mkdirs (java.io.File. root))

(def hd (tools/open root))
(def p (h/project-add! hd {:root-path root :name "t" :default-manifest "default"}))
(h/scope-add! hd {:project-id (:id p) :rel-path "src" :is-root false})

(defn call [name & [args]] (tools/dispatch hd root nil name (or args {})))

(check "board starts empty"
       "open (0)\nin_progress (0)\nblocked (0)\ndone (0)\ncancelled (0)"
       (:stdout (call "board")))

(def added (call "task_add" {:title "wire tools into chat" :priority "high" :scope "src"}))
(check "task_add ok" :ok (:status added))
(def tid (subs (:stdout added) (count "added ")))
(check "task_add returns id" true (str/starts-with? tid "faraday-"))

(let [r (call "task_list")]
  (check "task_list shows the task" true (str/includes? (:stdout r) "wire tools into chat")))

(let [r (call "task_set_status" {:id tid :status "in_progress"})]
  (check "task_set_status ok" :ok (:status r))
  (check "task_set_status moved" true (str/includes? (:stdout r) "in_progress")))

(check-true "scope_list shows src" (str/includes? (:stdout (call "scope_list")) "src"))

(let [r (call "hooks_for" {:rel-path "src"})]
  (check "hooks_for ok" :ok (:status r))
  (check-true "hooks_for empty vector" (str/includes? (:stdout r) ":hooks-vector []")))

(check "unknown tool errors" :error (:status (call "nope")))

(let [tp (tools/tool-prompt)]
  (check-true "prompt lists run_shell" (str/includes? tp "run_shell"))
  (check-true "prompt lists board" (str/includes? tp "board"))
  (check-true "prompt lists task_add" (str/includes? tp "task_add")))

(println "ALL TOOLS TESTS PASSED")
