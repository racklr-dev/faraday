;; faraday-agent harness test: the operator surface (launch/steer/observe N
;; agents) over the yieldable scheduler + middleware spine. Uses fakes for
;; faraday.pool and jolt.microvm (no KVM/libkrun), one thread-keyed rap server
;; per harness, and a thread store whose sandbox is a fake handle. A
;; CountDownLatch-in-atom blocks the fake exec so steering is deterministic.
;; Run: cd agent && jolt run test/faraday/harness_test.clj
(ns jolt.test.agent-harness
  (:require [faraday.agent.harness :as harness]
            [faraday.thread :as thread]
            [faraday.rap :as rap]
            [faraday.ledger :as ldg]
            [faraday.fanout :as fanout]
            [faraday.pool :as pool]
            [faraday.memo :as memo]
            [faraday.memory :as memory]
            [jolt.microvm :as microvm]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/faraday-harness-test-" (System/nanoTime)))

;; -- fakes: fake pool + blocking-able exec -----------------------------------
(def acquired (atom []))
(def released (atom []))
(def hibernates (atom []))
(def exec-cmds (atom []))
(def exec-result (atom {:stdout "hello\n" :stderr "" :exit 0}))
(def exec-gate (atom (promise)))
(def exec-started (atom false))

(defn reset-fakes! []
  (reset! acquired [])
  (reset! released [])
  (reset! hibernates [])
  (reset! exec-cmds [])
  (reset! exec-result {:stdout "hello\n" :stderr "" :exit 0})
  (reset! exec-gate (promise))
  (reset! exec-started false))

(defn fake-acquire [_ request]
  (swap! acquired conj request)
  {:id "h1" :vm {:fake? true} :boot-opts {}})

(defn fake-release [_ handle]
  (swap! released conj (:id handle))
  0)

(defn fake-hibernate [_ handle]
  (swap! hibernates conj (:id handle))
  (str "snap_" (:id handle)))

(defn fake-restore [_ snap]
  {:id (subs snap 5) :vm {:fake? true} :boot-opts {}})

(defn fake-exec [_vm command & {:keys [cwd timeout-ms]}]
  (swap! exec-cmds conj command)
  @exec-result)

(defn fake-exec-blocking [_vm command & {:keys [cwd timeout-ms]}]
  (reset! exec-started true)
  @@exec-gate
  (swap! exec-cmds conj command)
  @exec-result)

(defn wait-until [pred timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (cond
        (pred) true
        (> (- (System/currentTimeMillis) start) timeout-ms) false
        :else (do (Thread/sleep 5) (recur))))))

(defmacro with-fakes [exec-fn & body]
  `(with-redefs [faraday.pool/acquire fake-acquire
                 faraday.pool/release fake-release
                 faraday.pool/hibernate fake-hibernate
                 faraday.pool/restore fake-restore
                 jolt.microvm/exec ~exec-fn]
     ~@body))

(defn new-harness [ledg mem memy rt tmp]
  (let [store (thread/new-store {:pool ::pool :repo-root (str tmp "/repos")})
        srv (rap/new-server {:fanout rt :run-hook (thread/rap-hook store)})]
    (harness/new-harness {:srv srv :store store :rt rt :mem memy})))

(defn mk-manifest [result & {:as overrides}]
  (merge {:v 1
          :loop-id "loop_harness"
          :name "harness loop"
          :agent {:kind "script"
                  :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "hi"]}}}
                           {:done result}]}
          :max-turns 8}
         overrides))

;; -- launch N + wait-all + observe -------------------------------------------
(with-fakes fake-exec
  (reset-fakes!)
  (let [base (tmp-dir)
        l (ldg/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        memy (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        h (new-harness l m memy rt base)
        ctrls (harness/launch! h {:n 3 :manifest (mk-manifest "ok")
                                  :task "shared goal"})]
    (check "three launched" 3 (count ctrls))
    (check "three distinct thread-ids" 3 (count (set (map :thread-id ctrls))))
    (check "thread-ids are thr_ prefixed" true
           (every? #(clojure.string/starts-with? % "thr_") (map :thread-id ctrls)))
    (let [outs (harness/wait-all h)]
      (check "all agents done" [:done :done :done] (mapv #(get-in % [:outcome :status]) outs))
      (check "all agents result ok" ["ok" "ok" "ok"] (mapv #(get-in % [:outcome :result]) outs))
      (check "three execs total" 3 (count @exec-cmds)))
    (let [obs (harness/observe h (:thread-id (first ctrls)))]
      (check "observe status done" :done (:status obs))
      (check-true "observe history non-empty" (pos? (count (:history obs))))
      (check "observe salient empty" {} (:salient obs))
      (check "observe memo-keys empty" [] (:memo-keys obs)))))

;; -- steer: declarative :goal stop-condition mid-run ---------------------------
(with-fakes fake-exec-blocking
  (reset-fakes!)
  (let [base (tmp-dir)
        l (ldg/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        memy (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        h (new-harness l m memy rt base)
        manifest (mk-manifest "never" :agent
                              {:kind "script"
                               :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "a"]}}}
                                        {:tool-call {:name "run_shell" :args {:argv ["echo" "b"]}}}
                                        {:tool-call {:name "run_shell" :args {:argv ["echo" "c"]}}}
                                        {:done "never"}]})
        [ctrl] (harness/launch! h {:n 1 :manifest manifest})]
    (harness/steer! h (:thread-id ctrl) {:goal {:path [:turns] :pred :>= :value 1}})
    (deliver @exec-gate true)
    (let [out (first (harness/wait-all h))]
      (check "goal steered status" :done (:status out))
      (check "goal steered stop-reason" :goal (get-in out [:outcome :stop-reason]))
      (check "goal steered met" true (get-in out [:outcome :met]))
      (check "goal steered one turn" 1 (get-in out [:outcome :turns]))
      (check "goal steered one exec" 1 (count @exec-cmds)))))

;; -- steer: cancel stops at the next goal check --------------------------------
(with-fakes fake-exec-blocking
  (reset-fakes!)
  (let [base (tmp-dir)
        l (ldg/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        memy (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        h (new-harness l m memy rt base)
        manifest (mk-manifest "never" :agent
                              {:kind "script"
                               :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "a"]}}}
                                        {:tool-call {:name "run_shell" :args {:argv ["echo" "b"]}}}
                                        {:done "never"}]})
        [ctrl] (harness/launch! h {:n 1 :manifest manifest})]
    (check-true "cancel: exec actually started" (wait-until #(deref exec-started) 5000))
    (harness/steer! h (:thread-id ctrl) {:cancel true})
    (deliver @exec-gate true)
    (let [out (first (harness/wait-all h))]
      (check "cancel recorded directive" [:cancel] (:directives out))
      (check "cancel stopped status" :done (:status out))
      (check "cancel one exec" 1 (count @exec-cmds)))))

;; -- steer: user-input directive is durable + observable -----------------------
(with-fakes fake-exec-blocking
  (reset-fakes!)
  (let [base (tmp-dir)
        l (ldg/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        memy (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        h (new-harness l m memy rt base)
        [ctrl] (harness/launch! h {:n 1 :manifest (mk-manifest "ok")})]
    (check-true "user-input: exec actually started" (wait-until #(deref exec-started) 5000))
    (harness/steer! h (:thread-id ctrl) {:user-input "please stop soon"})
    (deliver @exec-gate true)
    (let [out (first (harness/wait-all h))]
      (check "directive recorded" ["please stop soon"] (:directives out))
      (let [ui (filter #(= "user-input" (:kind %)) (:history out))]
        (check "user-input message durable" 1 (count ui))
        (check "user-input content" "please stop soon" (get-in (first ui) [:payload :content]))))))

;; -- observe surfaces salient memory by thread-id ------------------------------
(with-fakes fake-exec
  (reset-fakes!)
  (let [base (tmp-dir)
        l (ldg/new-ledger {:path (str base "/ledger.db")})
        m (memo/new-memo {:path (str base "/memo")})
        memy (memory/new-memory {:path (str base "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        h (new-harness l m memy rt base)
        [ctrl] (harness/launch! h {:n 1 :manifest (mk-manifest "ok")})]
    (harness/wait-all h)
    (memory/note! memy (:thread-id ctrl) :goal "port the narrow waist")
    (memory/demote! memy (:thread-id ctrl) :goal)
    (let [obs (harness/observe h (:thread-id ctrl))]
      (check "observe salient empty after demote" {} (:salient obs))
      (check "observe breadcrumbs has goal" {:goal "port the narrow waist"} (:breadcrumbs obs)))))

(println "ALL HARNESS TESTS PASSED")
