;; jolt-rap unit test: run Tool Invocations against a faked vm-pool/microvm and
;; a real ledger/fanout, exercising invoke/cancel/close + callbacks + subscriptions.
;; Run: cd jolt-rap && jolt run test/jolt/rap_test.clj
(ns jolt.test.rap
  (:require [jolt.rap :as rap]
            [jolt.ledger :as ldg]
            [jolt.fanout :as fanout]
            [jolt.vm-pool :as pool]
            [jolt.microvm :as microvm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn err-of [thunk]
  (try (thunk) ::no-throw
       (catch Exception e e)))

(defn err-type [thunk]
  (let [e (err-of thunk)]
    (if (= ::no-throw e) ::no-throw (get (ex-data e) :type))))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/jolt-rap-test-" (System/nanoTime)))

;; -- fakes for jolt.vm-pool / jolt.microvm -----------------------------------
(def acquired (atom []))
(def released (atom []))
(def exec-cmds (atom []))
(def exec-result (atom {:stdout "hello\n" :stderr "" :exit 0}))
(def exec-block? (atom false))  ; when true, fake-exec spins until cleared

(defn reset-fakes! []
  (reset! acquired [])
  (reset! released [])
  (reset! exec-cmds [])
  (reset! exec-result {:stdout "hello\n" :stderr "" :exit 0})
  (reset! exec-block? false))

(defn fake-acquire [p request]
  (swap! acquired conj request)
  {:id "h1" :image "img" :vm {:fake? true} :boot-opts {}})

(defn fake-release [p handle]
  (swap! released conj (:id handle))
  0)

(defn fake-exec [vm command & {:keys [cwd timeout-ms]}]
  (swap! exec-cmds conj command)
  (loop [] (when @exec-block? (Thread/sleep 5) (recur)))
  @exec-result)

(defmacro with-fakes [& body]
  `(with-redefs [jolt.vm-pool/acquire fake-acquire
                 jolt.vm-pool/release fake-release
                 jolt.microvm/exec fake-exec]
     ~@body))

;; -- helpers -----------------------------------------------------------------
(defn new-srv [& [overrides]]
  (let [l (ldg/new-ledger {:path (str (tmp-dir) "/ledger.db")})
        rt (fanout/new-runtime {:ledger l})]
    {:srv (rap/new-server (merge {:vm-pool {:fake-pool true} :fanout rt} overrides))
     :ledger l}))

(defn sample-call [& [overrides]]
  (merge {:v 1 :thread-id "thr_1" :name "run_shell" :args {:argv ["/bin/echo" "ok"]}}
         overrides))

(defn await-flag [pred timeout-ms]
  (let [start (System/currentTimeMillis)]
    (loop []
      (cond
        (pred) true
        (> (- (System/currentTimeMillis) start) timeout-ms) false
        :else (do (Thread/sleep 10) (recur))))))

;; -- new-server --------------------------------------------------------------
(check "new-server requires :vm-pool"
        true (instance? Exception (err-of #(rap/new-server {:fanout nil}))))
(check "new-server requires :fanout"
        true (instance? Exception (err-of #(rap/new-server {:vm-pool nil}))))

;; -- invoke happy path -------------------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [{:keys [srv ledger]} (new-srv)
        got (atom nil)
        _ (rap/on-result srv "call_1" #(reset! got %))
        cid (rap/invoke srv (sample-call {:call-id "call_1"}))]
    (check "invoke returns the explicit call-id" "call_1" cid)
    (check "on-result fired with the terminal result" true (await-flag #(some? @got) 5000))
    (check "terminal status ok" "ok" (:status @got))
    (check "terminal exit-code 0" 0 (:exit-code @got))
    (check "terminal segments carry stdout" [{:type "text" :content "hello\n"}] (:segments @got))
    (check "acquire built the sandbox request"
           {:v 1 :request-id "call_1" :argv ["/bin/echo" "ok"]}
           (first @acquired))
    (check "exec ran the shell-quoted argv" "'/bin/echo' 'ok'" (first @exec-cmds))
    (check "sandbox was released" ["h1"] @released)
    (let [msgs (ldg/history ledger "thr_1")]
      (check "report Thread Message emitted" "report" (:kind (first msgs)))
      (check "report status ok" "ok" (get-in (first msgs) [:payload :status]))
      (check "report result carries exit-code" {:exit-code 0} (get-in (first msgs) [:payload :result])))))

;; -- invoke assigns a call-id when absent ------------------------------------
(with-fakes
  (reset-fakes!)
  (let [{:keys [srv ledger]} (new-srv)
        cid (rap/invoke srv (sample-call))]
    (check "invoke assigns a call_ id" true (boolean (re-find #"^call_" cid)))
    (check "assigned call runs to a terminal"
            true (await-flag #(some? (get-in @(:state srv) [:calls cid :terminal])) 5000))))

;; -- subscribe before invoke -------------------------------------------------
(with-fakes
  (reset-fakes!)
  (let [{:keys [srv ledger]} (new-srv)
        frames (atom [])]
    (rap/subscribe srv "call_2" #(swap! frames conj %))
    (rap/invoke srv (sample-call {:call-id "call_2"}))
    (check "subscriber got exactly one frame" true (await-flag #(= 1 (count @frames)) 5000))
    (check "subscriber frame is the terminal" "ok" (:status (first @frames)))))

;; -- on-result replays a stored terminal -------------------------------------
(with-fakes
  (reset-fakes!)
  (let [{:keys [srv ledger]} (new-srv)
        done (atom nil)
        replay (atom nil)]
    (rap/on-result srv "call_3" #(reset! done %))
    (rap/invoke srv (sample-call {:call-id "call_3"}))
    (check "first on-result fires" true (await-flag #(some? @done) 5000))
    (rap/on-result srv "call_3" #(reset! replay %))
    (check "late on-result replays the terminal immediately" true (some? @replay))
    (check "replayed status matches" "ok" (:status @replay))))

;; -- policy hook -------------------------------------------------------------
(let [{:keys [srv ledger]} (new-srv {:policy-hook (fn [_] false)})]
  (check "policy hook denies invocation"
          :policy-vetoed (err-type #(rap/invoke srv (sample-call)))))

;; -- error exit --------------------------------------------------------------
(with-fakes
  (reset-fakes!)
  (reset! exec-result {:stdout "" :stderr "boom\n" :exit 2})
  (let [{:keys [srv ledger]} (new-srv)
        got (atom nil)]
    (rap/on-result srv "call_4" #(reset! got %))
    (rap/invoke srv (sample-call {:call-id "call_4"}))
    (check "error terminal fires" true (await-flag #(some? @got) 5000))
    (check "error status" "error" (:status @got))
    (check "error exit-code" 2 (:exit-code @got))
    (check "error segments carry stderr" [{:type "text" :content "boom\n"}] (:segments @got))
    (check "error message" "command exited 2" (get-in @got [:error :message]))))

;; -- cancel an in-flight invocation ------------------------------------------
(with-fakes
  (reset-fakes!)
  (reset! exec-block? true)
  (let [{:keys [srv ledger]} (new-srv)
        got (atom nil)]
    (rap/on-result srv "call_5" #(reset! got %))
    (rap/invoke srv (sample-call {:call-id "call_5"}))
    (check "worker acquired before cancel" true (await-flag #(pos? (count @acquired)) 5000))
    (rap/cancel srv "call_5")
    (reset! exec-block? false)
    (check "cancel emits terminal cancelled" true (await-flag #(some? @got) 5000))
    (check "terminal status cancelled" "cancelled" (:status @got))))

;; -- close a whole thread ----------------------------------------------------
(with-fakes
  (reset-fakes!)
  (reset! exec-block? true)
  (let [{:keys [srv ledger]} (new-srv)
        g1 (atom nil) g2 (atom nil)]
    (rap/on-result srv "c1" #(reset! g1 %))
    (rap/on-result srv "c2" #(reset! g2 %))
    (rap/invoke srv (sample-call {:call-id "c1" :thread-id "thr_x"}))
    (rap/invoke srv (sample-call {:call-id "c2" :thread-id "thr_x"}))
    (check "both workers acquired" true (await-flag #(= 2 (count @acquired)) 5000))
    (rap/close srv "thr_x")
    (reset! exec-block? false)
    (check "close cancelled both calls" true (await-flag #(and @g1 @g2) 5000))
    (check "first terminal cancelled" "cancelled" (:status @g1))
    (check "second terminal cancelled" "cancelled" (:status @g2))))

;; -- close! on a subscription and on the server ------------------------------
(with-fakes
  (reset-fakes!)
  (reset! exec-block? true)
  (let [{:keys [srv ledger]} (new-srv)]
    (rap/invoke srv (sample-call {:call-id "call_6"}))
    (check "worker acquired" true (await-flag #(pos? (count @acquired)) 5000))
    (let [sub (rap/subscribe srv "call_6" (fn [_]))]
      (check "sub registered" 1 (count (get-in @(:state srv) [:calls "call_6" :subs])))
      (rap/close! sub)
      (check "close! on subscription releases it"
              0 (count (get-in @(:state srv) [:calls "call_6" :subs]))))
    (rap/close! srv)
    (check "close! on server clears calls" {} (get-in @(:state srv) [:calls]))
    (reset! exec-block? false)))

(println "ALL RAP TESTS PASSED")
