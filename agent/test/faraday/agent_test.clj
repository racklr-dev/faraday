;; faraday.agent unit test: drive a scripted agent through N sandbox execs
;; over a faked vm-pool/microvm and a real ledger/fanout. Exercises run!,
;; run-all!, transcript shape, max-turns, and error recovery.
;; Run: cd agent && jolt run test/faraday/agent_test.clj
(ns jolt.test.agent
  (:require [faraday.agent :as agent]
            [faraday.rap :as rap]
            [faraday.ledger :as ldg]
            [faraday.fanout :as fanout]
            [faraday.pool :as pool]
            [jolt.microvm :as microvm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/faraday-agent-test-" (System/nanoTime)))

;; -- fakes for faraday.pool / jolt.microvm -----------------------------------
(def acquired (atom []))
(def released (atom []))
(def exec-cmds (atom []))
(def exec-result (atom {:stdout "hello\n" :stderr "" :exit 0}))

(defn reset-fakes! []
  (reset! acquired [])
  (reset! released [])
  (reset! exec-cmds [])
  (reset! exec-result {:stdout "hello\n" :stderr "" :exit 0}))

(defn fake-acquire [p request]
  (swap! acquired conj request)
  {:id "h1" :image "img" :vm {:fake? true} :boot-opts {}})

(defn fake-release [p handle]
  (swap! released conj (:id handle))
  0)

(defn fake-exec [vm command & {:keys [cwd timeout-ms]}]
  (swap! exec-cmds conj command)
  @exec-result)

(defmacro with-fakes [& body]
  `(with-redefs [faraday.pool/acquire fake-acquire
                 faraday.pool/release fake-release
                 jolt.microvm/exec fake-exec]
     ~@body))

(defn new-srv []
  (let [l (ldg/new-ledger {:path (str (tmp-dir) "/ledger.db")})
        rt (fanout/new-runtime {:ledger l})]
    (rap/new-server {:vm-pool {:fake-pool true} :fanout rt})))

(defn await-flag [pred timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (cond
        (pred) true
        (> (- (System/currentTimeMillis) start) timeout-ms) false
        :else (do (Thread/sleep 10) (recur))))))

;; -- run! happy path: three sandbox execs, then done -------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        frames (atom [])
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv ["echo" "one"]} :display-as "echo one"}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "two"]} :display-as "echo two"}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "three"]} :display-as "echo three"}}
            {:done "all three ran"}])
        out (agent/run! {:rap srv :agent a :task "run three commands"
                         :thread-id "thr_1" :on-frame #(swap! frames conj %)})]
    (check "status done" :done (:status out))
    (check "result" "all three ran" (:result out))
    (check "three turns" 3 (:turns out))
    (check "three sandbox execs" 3 (count @exec-cmds))
    (check "shell-quoted argv" ["'echo' 'one'" "'echo' 'two'" "'echo' 'three'"] @exec-cmds)
    (check "three frames streamed" 3 (count @frames))
    (check "all frames ok" ["ok" "ok" "ok"] (map :status @frames))
    (check "transcript roles"
           [:user :tool-call :tool-result :tool-call :tool-result :tool-call :tool-result :assistant]
           (map :role (:transcript out)))
    (check "tool-result carries segments"
           [{:type "text" :content "hello\n"}]
           (get-in out [:transcript 2 :segments]))
    (check "sandboxes released" ["h1" "h1" "h1"] @released)
    (let [msgs (ldg/history (-> srv :fanout :ledger) "thr_1")]
      (check "report messages emitted" 3 (count msgs))
      (check "report kind" "report" (:kind (first msgs))))))

;; -- max-turns caps the loop -------------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv ["echo" "a"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "b"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "c"]}}}
            {:done "never reached"}])
        out (agent/run! {:rap srv :agent a :thread-id "thr_cap" :max-turns 2})]
    (check "max-turns status" :max-turns (:status out))
    (check "only two turns executed" 2 (:turns out))
    (check "only two execs" 2 (count @exec-cmds))))

;; -- step error surfaces as :error -------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (fn [_ctx] (throw (ex-info "boom" {:type :boom})))
        out (agent/run! {:rap srv :agent a :thread-id "thr_err"})]
    (check "step error status" :error (:status out))
    (check "step error message" "boom" (:error out))))

;; -- invalid action shape surfaces as :error ---------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (fn [_ctx] {:nonsense true})
        out (agent/run! {:rap srv :agent a :thread-id "thr_bad"})]
    (check "invalid action status" :error (:status out))
    (check "invalid action message mentions action"
           true (boolean (re-find #"invalid action" (:error out))))))

;; -- invoke failure becomes an error result the agent can recover from -------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv []}}}  ; bad: empty argv
            {:tool-call {:name "run_shell" :args {:argv ["echo" "recovered"]}}}
            {:done "recovered"}])
        out (agent/run! {:rap srv :agent a :thread-id "thr_rec"})]
    (check "recovered status done" :done (:status out))
    (check "recovered result" "recovered" (:result out))
    (check "two turns" 2 (:turns out))
    (check "first result errored" "error" (get-in out [:transcript 1 :status]))
    (check "second result ok" "ok" (get-in out [:transcript 3 :status]))
    (check "only the recovered exec ran" 1 (count @exec-cmds))
    (check "recovered exec" ["'echo' 'recovered'"] @exec-cmds)))

;; -- run-all! drives N agents concurrently, one per sandbox stream -----------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        mk (fn [n] (agent/scripted-agent [{:tool-call {:name "run_shell" :args {:argv ["echo" (str n)]}}}
                                          {:done n}]))
        outs (agent/run-all! srv [{:agent (mk "a")} {:agent (mk "b")} {:agent (mk "c")}])]
    (check "three outcomes" 3 (count outs))
    (check "all done" [:done :done :done] (map :status outs))
    (check "results in order" ["a" "b" "c"] (map :result outs))
    (check "three execs across agents" 3 (count @exec-cmds))))

;; -- :goal stop-condition stops the loop the moment it passes ---------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv ["echo" "a"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "b"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "c"]}}}
            {:done "never"}])
        out (agent/run! {:rap srv :agent a :thread-id "thr_goal"
                         :goal #(>= (:turns %) 2)})]
    (check "goal stops status" :done (:status out))
    (check "goal stop-reason" :goal (:stop-reason out))
    (check "goal met true" true (:met out))
    (check "goal stopped after 2 turns" 2 (:turns out))
    (check "goal ran 2 execs" 2 (count @exec-cmds))))

;; -- :goal unmet when the agent finishes first -------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent [{:done "early"}])
        out (agent/run! {:rap srv :agent a :thread-id "thr_goal2"
                         :goal #(>= (:turns %) 5)})]
    (check "agent done status" :done (:status out))
    (check "agent done stop-reason" :agent (:stop-reason out))
    (check "goal unmet when agent finished" false (:met out))
    (check "no execs for unmet goal" 0 (count @exec-cmds))))

;; -- :goal unmet on max-turns ------------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv ["echo" "x"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "y"]}}}
            {:tool-call {:name "run_shell" :args {:argv ["echo" "z"]}}}])
        out (agent/run! {:rap srv :agent a :thread-id "thr_goal3" :max-turns 1
                         :goal #(>= (:turns %) 99)})]
    (check "max-turns with unmet goal" :max-turns (:status out))
    (check "max-turns goal unmet" false (:met out))))

;; -- invalid :goal rejected --------------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        a (agent/scripted-agent [{:done "x"}])
        err (try (agent/run! {:rap srv :agent a :goal "not-a-fn"}) ::no-throw
                 (catch Exception e (ex-message e)))]
    (check "invalid goal throws" "run! :goal must be a predicate fn of ctx" err)))

(println "ALL AGENT TESTS PASSED")
