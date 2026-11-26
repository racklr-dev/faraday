;; faraday-harness test: DB CRUD, prefix-nested scope hook resolution, the
;; must-be-able-to-fail hook rejection, task-start reification, and the
;; end-to-end honest run (expected⊆ran + blocking⊆pass).
;; Run: cd harness && jolt run test/faraday/harness_test.clj
(ns jolt.test.harness
  (:require [faraday.harness :as h]
            [faraday.harness.main :as main]
            [faraday.harness.config :as config]
            [faraday.harness.llm :as llm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-root []
  (str (System/getProperty "java.io.tmpdir") "/faraday-harness-" (System/nanoTime)))

(defn setup! []
  (let [root (tmp-root)
        _ (.mkdirs (java.io.File. (str root "/.faraday")))
        hd (h/new-harness {:path (str root "/.faraday/harness.db")})
        p (h/project-add! hd {:root-path root :name "t" :default-manifest "default"})
        rs (h/scope-add! hd {:project-id (:id p) :rel-path "" :is-root true})]
    {:root root :hd hd :project p :root-scope rs}))

;; -- DB CRUD -----------------------------------------------------------------
(let [{:keys [root hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src" :meta {:lang "clojure"}})
      srcdb (h/scope-add! hd {:project-id (:id project) :rel-path "src/db" :meta {:test-cmd "clj -M:test"}})]
  (check "project round-trips" "t" (:name (h/project hd (:id project))))
  (check "project by root" (:id project) (:id (h/project-by-root hd root)))
  (check "three scopes" 3 (count (h/scope-list hd (:id project))))
  (check "scope meta parsed" "clojure" (get-in (h/scope hd (:id src)) [:meta :lang]))
  (check "scope-by-rel finds src/db" (:id srcdb) (:id (h/scope-by-rel hd (:id project) "src/db"))))

;; -- hook add: happy path + must-be-able-to-fail + validation -----------------
(let [{:keys [hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      good (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                            :cell :step :position :post :kind :slop-cop
                            :how {:command ["grep" "-q" "Copyright" "src/file.clj"]
                                  :fixture {:argv ["grep" "-q" "Copyright" "{fixture}"]
                                            :good ";; Copyright\n(def x 1)\n"
                                            :bad "(def x 1)\n"}}
                            :why "blocks headerless files; fails when the fixture lacks Copyright"
                            :gate :blocking})]
  (check-true "proven hook stored" (some? good))
  (check "hook how round-trips" ["grep" "-q" "Copyright" "src/file.clj"]
         (get-in (h/hook hd (:id good)) [:how :command]))
  (check "hook gate stored" "blocking" (:gate (h/hook hd (:id good))))
  (check "one hook listed" 1 (count (h/hook-list hd (:id project))))
  (check-true "vacuous always-pass hook rejected"
              (try (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                                    :cell :step :position :post :kind :gate
                                    :how {:command ["sh" "-c" "exit 0"]
                                          :fixture {:argv ["sh" "-c" "cat {fixture} >/dev/null; exit 0"]
                                                    :good "a" :bad "b"}}
                                    :why "always passes"})
                   false
                   (catch Exception _ true)))
  (check-true "always-fail hook rejected"
              (try (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                                    :cell :step :position :post :kind :gate
                                    :how {:command ["sh" "-c" "exit 1"]
                                          :fixture {:argv ["sh" "-c" "cat {fixture} >/dev/null; exit 1"]
                                                    :good "a" :bad "b"}}
                                    :why "always fails"})
                   false
                   (catch Exception _ true)))
  (check-true "empty why rejected"
              (try (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                                    :cell :step :position :post :kind :gate
                                    :how {:command ["true"]} :why ""})
                   false
                   (catch Exception _ true)))
  (check-true "bad how shape rejected"
              (try (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                                    :cell :step :position :post :kind :gate
                                    :how {:nonsense 1} :why "x"})
                   false
                   (catch Exception _ true))))

;; -- prefix-nested scope resolution -------------------------------------------
(let [{:keys [hd project root-scope]} (setup!)
      rs root-scope
      _ (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      _ (h/scope-add! hd {:project-id (:id project) :rel-path "src/db"})
      src (h/scope-by-rel hd (:id project) "src")
      srcdb (h/scope-by-rel hd (:id project) "src/db")
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id rs)
                         :cell :orient :position :pre :kind :check
                         :how {:command ["true"]} :why "root invariant"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                         :cell :step :position :post :kind :slop-cop
                         :how {:command ["true"]} :why "no slop"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id srcdb)
                         :cell :finalize :position :post :kind :fast-test
                         :how {:command ["true"]} :why "fast tests"})
      res-db (h/resolve-hooks hd (:id project) "src/db")
      res-src (h/resolve-hooks hd (:id project) "src")
      res-root (h/resolve-hooks hd (:id project) "")]
  (check "src/db sees 3 hooks" 3 (count (:hooks-vector res-db)))
  (check "src sees 2 hooks" 2 (count (:hooks-vector res-src)))
  (check "root sees 1 hook" 1 (count (:hooks-vector res-root)))
  (check "shallow-first order" ["check" "slop-cop" "fast-test"]
         (mapv :kind (:hooks-vector res-db)))
  (check "hooks grouped by cell/position" [:pre] (vec (keys (get-in res-db [:hooks :orient]))))
  (check "step post group has slop-cop" ["slop-cop"]
         (mapv :kind (get-in res-db [:hooks :step :post]))))

;; -- task start reifies loop-as-data ------------------------------------------
(let [{:keys [hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                         :cell :step :position :post :kind :lint
                         :how {:command ["true"]} :why "lint"})
      task (h/task-add! hd {:project-id (:id project) :scope-id (:id src)
                            :title "wire db"})
      started (h/task-start hd (:id task))]
  (check "task start marks in_progress" "in_progress" (:status (h/task hd (:id task))))
  (check "reified one hook" 1 (count (:hooks-vector started)))
  (check "reified scope" "src" (:scope started))
  (check "no manifest override" nil (:manifest started))
  (h/task-start hd (:id task))
  (check "task start idempotent (stays in_progress)" "in_progress"
         (:status (h/task hd (:id task)))))

;; -- end-to-end honest run: green ---------------------------------------------
(let [{:keys [root hd project]} (setup!)
      rs (h/scope-by-rel hd (:id project) "")
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id rs)
                         :cell :orient :position :pre :kind :check
                         :how {:command ["true"]} :why "pre check passes" :gate :blocking})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                         :cell :finalize :position :post :kind :gate
                         :how {:command ["true"]} :why "post gate passes" :gate :blocking})
      task (h/task-add! hd {:project-id (:id project) :scope-id (:id src) :title "run me"})
      out (main/run-task! root (:id task))]
  (check "agent ran done" :done (:agent-status out))
  (check "two hook runs recorded" 2 (:hook-runs out))
  (check-true "every expected hook ran" (:expected-ran? out))
  (check-true "turn is green" (:green? out))
  (check "nothing missing" [] (:missing out))
  (check "no blocking failures" [] (:blocking-failed out))
  (check "two verdicts recorded" ["pass" "pass"] (sort (mapcat :verdict (:verdicts out)))))

;; -- end-to-end honest run: failing blocking hook ------------------------------
(let [{:keys [root hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                         :cell :finalize :position :post :kind :gate
                         :how {:command ["false"]} :why "post gate fails" :gate :blocking})
      task (h/task-add! hd {:project-id (:id project) :scope-id (:id src) :title "fail me"})
      out (main/run-task! root (:id task))]
  (check "agent still ran done" :done (:agent-status out))
  (check-true "turn is not green" (not (:green? out)))
  (check-true "every expected hook still ran" (:expected-ran? out))
  (check "one blocking failure" 1 (count (:blocking-failed out)))
  (check "failure verdict is fail" ["fail"] (mapcat :verdict (:verdicts out))))

;; -- end-to-end honest run: advisory failure stays green -----------------------
(let [{:keys [root hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      _ (h/hook-add! hd {:project-id (:id project) :scope-id (:id src)
                         :cell :finalize :position :post :kind :lint
                         :how {:command ["false"]} :why "advisory lint fails" :gate :advisory})
      task (h/task-add! hd {:project-id (:id project) :scope-id (:id src) :title "advisory"})
      out (main/run-task! root (:id task))]
  (check-true "advisory failure is still green" (:green? out))
  (check "verdict recorded as fail" ["fail"] (mapcat :verdict (:verdicts out))))

;; -- end-to-end: model manifest routes through the middleware spine ---------
(let [{:keys [root hd project]} (setup!)
      src (h/scope-add! hd {:project-id (:id project) :rel-path "src"})
      task (h/task-add! hd {:project-id (:id project) :scope-id (:id src)
                            :title "model me"})
      manifest {:v 1 :loop-id "loop-model" :name "model task"
                :agent {:kind "model"} :max-turns 4}]
  (with-redefs [config/env (fn [k] (when (= k "DEEPSEEK_API_KEY") "test-key"))
                llm/provider (fn [_cfg] (fn [_req] {:done {:result :ok}}))]
    (let [out (main/run-task! root (:id task) :manifest manifest)]
      (check "model agent ran done" :done (:agent-status out))
      (check "model result recorded" {:result :ok} (:agent-result out))
      (check-true "model turn is green (no hooks)" (:green? out)))))

(let [interrupt (atom false)
      start (System/currentTimeMillis)
      fut (future (h/run-command-interruptible ["sleep" "5"] 30000 interrupt))
      _ (Thread/sleep 150)
      _ (reset! interrupt true)
      r (deref fut 5000 :no-result)]
  (check-true "interruptible command stops early" (:interrupted? r))
  (check-true "interruptible command returns fast" (< (- (System/currentTimeMillis) start) 5000)))

(let [lines (atom [])
      r (h/run-command-streaming ["sh" "-c" "printf 'a\\nb\\n'"] 8000 (fn [l] (swap! lines conj l)))]
  (check "streaming run-command returns exact out"
         {:exit 0 :out "a\nb\n" :timeout? false}
         (select-keys r [:exit :out :timeout?]))
  (check "streaming run-command saw each line in order" ["a" "b"] @lines))

(check "streaming run-command preserves unterminated output"
       {:exit 0 :out "x" :timeout? false}
       (select-keys (h/run-command-streaming ["sh" "-c" "printf 'x'"] 8000 nil) [:exit :out :timeout?]))

(let [interrupt (atom false)
      start (System/currentTimeMillis)
      fut (future (h/run-command-streaming ["sleep" "5"] 30000 nil :interrupt interrupt))
      _ (Thread/sleep 150)
      _ (reset! interrupt true)
      r (deref fut 5000 :no-result)]
  (check-true "streaming command stops early" (:interrupted? r))
  (check-true "streaming command returns fast" (< (- (System/currentTimeMillis) start) 5000)))

(println "ALL HARNESS TESTS PASSED")

(shutdown-agents)
