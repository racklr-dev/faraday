;; faraday-agent model step-fn test: valid-action?, routed-provider, the
;; repair->validate->escalate->circuit-break chain on a weak model, and
;; interchangeability with scripted-agent through run!.
;; Run: cd agent && jolt run test/faraday/model_test.clj
(ns jolt.test.agent-model
  (:require [faraday.agent :as agent]
            [faraday.agent.model :as model]
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

(defn check-true [label got] (check label true got))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/faraday-model-test-" (System/nanoTime)))

;; -- fakes for the run! interchangeability check ----------------------------
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

;; -- valid-action? -----------------------------------------------------------
(check-true "valid-action? done" (model/valid-action? {:done "x"}))
(check-true "valid-action? tool-call"
            (model/valid-action? {:tool-call {:name "run_shell" :args {:argv ["echo"]}}}))
(check-true "valid-action? rejects garbage map"
            (not (model/valid-action? {:garbage true})))
(check-true "valid-action? rejects non-map" (not (model/valid-action? "hi")))
(check-true "valid-action? rejects empty argv"
            (not (model/valid-action? {:tool-call {:name "x" :args {:argv []}}})))
(check-true "valid-action? rejects whitespace name"
            (not (model/valid-action? {:tool-call {:name "run shell" :args {:argv ["a"]}}})))

;; -- routed-provider: alias/role config -------------------------------------
(let [calls (atom [])
      rp (model/routed-provider
          {:fast (fn [r] (swap! calls conj :fast) {:done :fast})
           :strong (fn [r] (swap! calls conj :strong) {:done :strong})}
          {:default :fast :escalate :strong})]
  (check "routed-provider fast role" {:done :fast} (rp {:role :fast}))
  (check "routed-provider strong role" {:done :strong} (rp {:role :strong}))
  (check "routed-provider called in order" [:fast :strong] @calls)
  (let [err (try (rp {:role :missing}) ::no-throw (catch Exception e (ex-message e)))]
    (check-true "routed-provider missing alias throws"
                (boolean (re-find #"no provider for role alias" err)))))

;; -- valid model returns the action unchanged --------------------------------
(let [a (model/model-agent {:provider (fn [_] {:done "direct"})})]
  (check "model-agent valid direct" {:done "direct"} (a {:turns 0 :transcript []})))

;; -- repair: a weak default role is rescued by a repair re-call ----------------
(let [calls (atom [])
      provider (fn [request]
                 (swap! calls conj (:role request))
                 (if (= :repair (:role request))
                   {:tool-call {:name "run_shell" :args {:argv ["echo" "ok"]}}}
                   {:garbage true}))
      a (model/model-agent {:provider provider :max-repairs 2})
      out (a {:turns 0 :transcript []})]
  (check "repair returns valid tool-call"
         {:tool-call {:name "run_shell" :args {:argv ["echo" "ok"]}}} out)
  (check "repair called default then repair" [:default :repair] @calls))

;; -- escalate: weak default+repair, strong escalation role --------------------
(let [calls (atom [])
      weak (fn [r] (swap! calls conj :weak) {:garbage true})
      strong (fn [r] (swap! calls conj :strong) {:done "rescued"})
      a (model/model-agent
         {:providers {:weak weak :strong strong}
          :role-config {:default :weak :repair :weak :escalate :strong}
          :max-repairs 1})
      out (a {:turns 0 :transcript []})]
  (check "escalate returns strong result" {:done "rescued"} out)
  (check "escalation order: default+repair+escalate" [:weak :weak :strong] @calls))

;; -- circuit-break: all roles weak, breaker opens after threshold --------------
(let [calls (atom [])
      provider (fn [r] (swap! calls conj (:role r)) {:garbage true})
      a (model/model-agent {:provider provider :max-repairs 1 :max-escalations 1
                            :breaker-threshold 2})
      s1 (a {:turns 0 :transcript []})
      s2 (a {:turns 0 :transcript []})
      calls-before-open @calls
      s3 (a {:turns 0 :transcript []})
      calls-after-open @calls]
  (check "first failure step-invalid" {:done {:error :step-invalid :failures 1}} s1)
  (check "second failure step-invalid" {:done {:error :step-invalid :failures 2}} s2)
  (check "third step circuit-open" {:done {:error :circuit-open}} s3)
  (check "no provider call after circuit opens" calls-before-open calls-after-open))

;; -- interchangeable with scripted-agent through run! -------------------------
(with-fakes
  (reset-fakes!)
  (let [srv (new-srv)
        i (atom 0)
        actions [{:tool-call {:name "run_shell" :args {:argv ["echo" "m"]}}}
                  {:done "model-done"}]
        provider (fn [_] (let [n (swap! i inc)] (nth actions (dec n))))
        a (model/model-agent {:provider provider})
        out (agent/run! {:rap srv :agent a :task "go" :thread-id "thr_model"})]
    (check "model-agent run! status" :done (:status out))
    (check "model-agent run! result" "model-done" (:result out))
    (check "model-agent run! one turn" 1 (:turns out))
    (check "model-agent run! one exec" 1 (count @exec-cmds))
    (check "model-agent run! released sandbox" ["h1"] @released)))

(println "ALL MODEL STEP-FN TESTS PASSED")
