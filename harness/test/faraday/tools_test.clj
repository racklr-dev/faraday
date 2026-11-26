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
  (check-true "prompt lists task_add" (str/includes? tp "task_add"))
  (check-true "prompt lists run_jolt" (str/includes? tp "run_jolt")))

(check-true "run-code-tool? knows run_jolt" (tools/run-code-tool? "run_jolt"))
(check "run-code-tool? rejects run_shell" false (tools/run-code-tool? "run_shell"))

(let [{:keys [argv path]} (tools/run-code-argv "run_jolt" "(println 1)" root)]
  (check "run-code-argv runs jolt on the scratch file" ["jolt" "run" path] argv)
  (check-true "run-code-argv writes a .clj file" (str/ends-with? path ".clj"))
  (check-true "run-code-argv file exists" (.isFile (java.io.File. path)))
  (check-true "run-code-argv file holds the code" (str/includes? (slurp path) "(println 1)")))

(let [a (:path (tools/run-code-argv "run_jolt" "(println 1)" root))
      b (:path (tools/run-code-argv "run_jolt" "(println 2)" root))]
  (check "run-code-argv names each call uniquely" false (= a b)))

(let [lp (tools/language-prompt)]
  (check-true "language prompt names run_jolt" (str/includes? lp "run_jolt"))
  (check-true "language prompt says where it runs" (str/includes? lp "project root"))
  (check-true "language prompt says when to prefer it" (str/includes? lp "Prefer run_jolt")))

(let [ots (tools/openai-tools)]
  (check-true "openai tools include run_jolt"
              (boolean (some #(= "run_jolt" (get-in % [:function :name])) ots))))

;; -- structured file tools (read_file / edit_file / write_file) --------------
(let [nl (str (char 10))]
  (let [f "notes.txt"
        _ (spit (str root "/" f) (str "alpha" nl "beta" nl "gamma" nl "delta" nl))]
    (check "read_file whole file" (str "alpha" nl "beta" nl "gamma" nl "delta") (:stdout (tools/read-file root {:path f})))
    (check "read_file line range" (str "beta" nl "gamma") (:stdout (tools/read-file root {:path f :start 2 :end 3})))
    (check "read_file read-only progress" false (:progress? (tools/read-file root {:path f})))
    (check "read_file missing errors" :error (:status (tools/read-file root {:path "nope.txt"})))))

(let [f "edit.txt"
      _ (spit (str root "/" f) "hello world")]
  (let [r (tools/edit-file root {:path f :old "world" :new "there"})]
    (check "edit_file ok" :ok (:status r))
    (check "edit_file content" "hello there" (slurp (str root "/" f)))
    (check "edit_file progress" true (:progress? r))))

(let [f "dup.txt"
      _ (spit (str root "/" f) "x x x")]
  (check "edit_file non-unique errors" :error (:status (tools/edit-file root {:path f :old "x" :new "y"}))))

(let [f "absent.txt"
      _ (spit (str root "/" f) "abc")]
  (check "edit_file missing old errors" :error (:status (tools/edit-file root {:path f :old "zzz" :new "y"}))))

(let [r (tools/write-file root {:path "sub/dir/out.txt" :content "payload"})]
  (check "write_file ok" :ok (:status r))
  (check-true "write_file creates parents" (.isFile (java.io.File. (str root "/sub/dir/out.txt"))))
  (check "write_file content" "payload" (slurp (str root "/sub/dir/out.txt"))))

(check-true "read_file is read-only" (contains? tools/read-only-tools "read_file"))

(let [ots (tools/openai-tools)]
  (check-true "openai tools include read_file" (boolean (some #(= "read_file" (get-in % [:function :name])) ots)))
  (check-true "openai tools include edit_file" (boolean (some #(= "edit_file" (get-in % [:function :name])) ots)))
  (check-true "openai tools include write_file" (boolean (some #(= "write_file" (get-in % [:function :name])) ots))))

(let [tp (tools/tool-prompt)]
  (check-true "prompt lists read_file" (str/includes? tp "read_file"))
  (check-true "prompt lists edit_file" (str/includes? tp "edit_file"))
  (check-true "prompt lists write_file" (str/includes? tp "write_file")))

(println "ALL TOOLS TESTS PASSED")
