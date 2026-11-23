;; faraday-agent loop-as-data test: manifest validate/store/load/rewrite,
;; run-manifest!, soak (pass + fail), and the promote/rollback gate over the
;; ledger pointer. Exercises the fake pool seam from agent_test, no KVM.
;; Run: cd agent && jolt run test/faraday/loop_test.clj
(ns jolt.test.agent-loop
  (:require [faraday.agent :as agent]
            [faraday.agent.loop :as loop]
            [faraday.contracts :as contracts]
            [faraday.rap :as rap]
            [faraday.ledger :as ldg]
            [faraday.fanout :as fanout]
            [faraday.pool :as pool]
            [faraday.memo :as memo]
            [jolt.microvm :as microvm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/faraday-loop-test-" (System/nanoTime)))

;; -- fakes (same seam as agent_test) ----------------------------------------
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

(defn new-memo [] (memo/new-memo {:path (str (tmp-dir) "/memo")}))

;; -- a valid scripted manifest ----------------------------------------------
(defn mk-manifest [result]
  {:v 1
   :loop-id "loop_test"
   :name "echo loop"
   :agent {:kind "script"
           :script [{:tool-call {:name "run_shell" :args {:argv ["echo" "hi"]} :display-as "hi"}}
                    {:done result}]}
   :max-turns 8})

;; -- contract: validate ------------------------------------------------------
(let [valid (loop/validate (mk-manifest "ok"))]
  (check-true "valid manifest decodes" (not (contracts/refusal? valid)))
  (check "validated loop-id" "loop_test" (:loop-id valid))
  (check "validated agent kind" "script" (get-in valid [:agent :kind]))
  (check "validated script length" 2 (count (get-in valid [:agent :script])))
  (check "validated max-turns" 8 (:max-turns valid)))

(let [r (loop/validate (dissoc (mk-manifest "ok") :loop-id))]
  (check-true "missing loop-id is a refusal" (contracts/refusal? r))
  (check "refusal names loop-id" ["loop-id"] (:path r)))

(let [r (loop/validate (assoc-in (mk-manifest "ok") [:agent :kind] "model"))]
  (check-true "unknown agent kind is a refusal" (contracts/refusal? r))
  (check "refusal names agent kind" ["agent" "kind"] (:path r)))

;; -- store!/key-of/load round-trip in memo -----------------------------------
(let [m (new-memo)
      manifest (mk-manifest "ok")
      k (loop/store! m manifest)]
  (check "key is 64-char hex" true (boolean (re-matches #"[0-9a-f]{64}" k)))
  (check "key-of agrees with store!" k (loop/key-of m manifest))
  (check "load round-trips loop-id" "loop_test" (:loop-id (loop/load-manifest m k)))
  (check "load round-trips done result" "ok" (get-in (loop/load-manifest m k) [:agent :script 1 :done])))

;; -- the agent rewrites the loop as data -------------------------------------
(let [m (new-memo)
      cur (mk-manifest "v1")
      candidate (loop/rewrite cur
                   (fn [manifest]
                     (-> manifest
                         (assoc :name "echo loop v2")
                         (assoc-in [:agent :script 1] {:done "v2"}))))]
  (check-true "rewrite yields valid data" (not (contracts/refusal? (loop/validate candidate))))
  (check "rewrite changed name" "echo loop v2" (:name candidate))
  (check "rewrite changed result" "v2" (get-in candidate [:agent :script 1 :done]))
  (check "rewrite left original untouched" "v1" (get-in cur [:agent :script 1 :done])))

;; -- run-manifest! drives the loop over a rap server -------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        out (loop/run-manifest! {:rap srv :manifest (mk-manifest "ok") :thread-id "thr_loop"})]
    (check "run-manifest status" :done (:status out))
    (check "run-manifest result" "ok" (:result out))
    (check "run-manifest one turn" 1 (:turns out))
    (check "run-manifest one exec" 1 (count @exec-cmds))
    (check "run-manifest released sandbox" ["h1"] @released)))

;; -- soak!: N runs, all must pass the harness --------------------------------
(with-fakes
  (reset-fakes!)
  (let [soak (loop/soak! {:manifest (mk-manifest "ok")
                          :server-fn new-srv
                          :n 3
                          :harness #(= :done (:status %))})]
    (check-true "soak passes when all runs are :done" (:passed? soak))
    (check "soak ran 3 runs" 3 (:runs soak))
    (check "soak outcomes are :done" [:done :done :done] (mapv :status (:outcomes soak)))
    (check "soak ran 3 execs total" 3 (count @exec-cmds))))

(with-fakes
  (reset-fakes!)
  (let [soak (loop/soak! {:manifest (mk-manifest "ok")
                          :server-fn new-srv
                          :n 3
                          :harness #(= "never" (:result %))})]
    (check-true "soak fails when harness rejects" (not (:passed? soak)))
    (check "soak still ran 3 runs" 3 (:runs soak))
    (check "soak outcomes results" ["ok" "ok" "ok"] (mapv :result (:outcomes soak)))))

;; -- promote-loop!: the gate re-points the ledger on success ------------------
(with-fakes
  (reset-fakes!)
  (let [m (new-memo)
        l (ldg/new-ledger {:path (str (tmp-dir) "/gate.db")})
        out (loop/promote-loop! {:memo m :ledger l :server-fn new-srv
                                 :manifest (mk-manifest "ok")
                                 :pointer "loop/active" :n 2})]
    (check "promote status" :promoted (:status out))
    (check "promote key is hex" true (boolean (re-matches #"[0-9a-f]{64}" (:key out))))
    (check "promote previous-key nil" nil (:previous-key out))
    (check "ledger re-pointed at new key" (:key out) (ldg/pointer l "loop/active"))
    (check "promoted manifest in memo" "ok" (get-in (loop/load-manifest m (:key out)) [:agent :script 1 :done]))))

;; -- promote-loop!: failed soak rolls the ledger pointer back ----------------
(with-fakes
  (reset-fakes!)
  (let [m (new-memo)
        l (ldg/new-ledger {:path (str (tmp-dir) "/gate2.db")})
        good (loop/promote-loop! {:memo m :ledger l :server-fn new-srv
                                  :manifest (mk-manifest "stable") :pointer "loop/active" :n 1})
        old-key (:key good)
        bad (loop/promote-loop! {:memo m :ledger l :server-fn new-srv
                                 :manifest (mk-manifest "broken") :pointer "loop/active" :n 1
                                 :harness #(= "stable" (:result %))})]
    (check "good promote succeeded" :promoted (:status good))
    (check "bad candidate rolled back" :rolled-back (:status bad))
    (check "rollback previous-key is old key" old-key (:previous-key bad))
    (check "rollback key is nil" nil (:key bad))
    (check "ledger still points at old key" old-key (ldg/pointer l "loop/active"))
    (check "rolled-back candidate NOT in memo" false (memo/contains? m (loop/key-of m (mk-manifest "broken"))))))

;; -- promote-loop!: invalid candidate is rejected before any soak ------------
(with-fakes
  (reset-fakes!)
  (let [m (new-memo)
        l (ldg/new-ledger {:path (str (tmp-dir) "/gate3.db")})
        out (loop/promote-loop! {:memo m :ledger l :server-fn new-srv
                                 :manifest (dissoc (mk-manifest "ok") :name)
                                 :pointer "loop/active"})]
    (check "reject status" :rejected (:status out))
    (check "reject reason" :invalid (:reason out))
    (check "reject refusal names name" ["name"] (get-in out [:refusal :path]))
    (check "no soak ran on reject" 0 (count @exec-cmds))
    (check "ledger pointer untouched" nil (ldg/pointer l "loop/active"))))

(println "ALL LOOP-AS-DATA TESTS PASSED")
