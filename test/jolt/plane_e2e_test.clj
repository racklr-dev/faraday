;; jolt-plane E2E (nc-916C): a harness drives N sandboxes over RAP through
;; the plane gate. No KVM: the VM boundary (start!/stop!) is faked via
;; with-redefs and the exec hook is injected into the runner, so each RAP
;; invoke runs the full gate -> spawn -> acquire -> exec -> release -> report
;; path and delivers one terminal result frame.
;; Run: cd jolt-plane && JOLT_CEDAR_LIB=<libcedar_policy.so> jolt run test/jolt/plane_e2e_test.clj
(ns jolt.test.plane-e2e
  (:require [jolt.plane :as plane]
            [jolt.vm-pool :as pool]
            [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]
            [jolt.microvm :as microvm]
            [jolt.rap :as rap]))

(def failures (atom 0))
(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (swap! failures inc)
        (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got))))))

;; double-quote char, so cedar source strings need no escapes
(def q (str (char 34)))

(def egress-policy
  [(str "forbid(principal, action, resource)"
        " when { action == Action::" q "RunSandbox" q " &&"
        "        resource.egress has allow_cidrs &&"
        "        resource.egress.allow_cidrs.containsAny([" q "8.8.8.8/32" q ", " q "0.0.0.0/0" q "]) };")
   (str "permit(principal, action, resource)"
        " when { action == Action::" q "RunSandbox" q " &&"
        "        resource.egress has default &&"
        "        resource.egress.default == " q "deny" q " };" )])

(def clean-egress
  {:egress-id "egr_clean" :dns ["1.1.1.1"] :allow-cidrs ["1.1.1.1/32"]
   :host-gateway true :default "deny"})

(def forbidden-egress
  {:egress-id "egr_bad" :dns ["1.1.1.1"] :allow-cidrs ["1.1.1.1/32" "8.8.8.8/32"]
   :host-gateway true :default "deny"})

(def image "oci://ghcr.io/jolt-lang/distroless-base@sha256:abc")

(defn tool-call
  "A RAP Tool Invocation (SPEC section 3). args is the tool arguments map;
  :argv is required and Sandbox Request fields (:image :cpu :mem-mb :egress)
  pass through to the plane runner via jolt.rap's call->request."
  [i & [args]]
  {:v 1 :call-id (str "call_" i) :thread-id (str "thr_" i) :name "run_shell"
   :args (merge {:argv ["/bin/echo" (str "sandbox-" i)]
                 :image image :cpu 1 :mem-mb 512 :egress {:ref "egr_clean"}}
                args)})

(defn invoke-sync
  "invoke over RAP and block on the terminal result frame."
  [srv call]
  (let [p (promise)
        cid (rap/invoke srv call)]
    (rap/on-result srv cid (fn [frame] (deliver p frame)))
    @p))

(defn with-e2e [f]
  (let [root (str "/tmp/jolt-plane-e2e-" (System/nanoTime))
        db (ledger/new-ledger {:path (str root "/ledger.db")})
        rt (fanout/new-runtime {:ledger db})
        p (pool/new-pool {:image image
                          :cache-dir (str root "/cache")
                          :max-warm 10})
        live (atom 0)
        max-live (atom 0)
        exec-hook (fn [h _]
                    (let [n (swap! live inc)]
                      (swap! max-live max n)
                      (try
                        (Thread/sleep 80)
                        {:exit 0 :stdout (str "ok-" n) :stderr ""}
                        (finally (swap! live dec)))))]
    (with-redefs [microvm/start! (fn [_] {:fake-vm true})
                  microvm/stop! (fn [_] nil)]
      (try
        (f {:ledger db :fanout rt :pool p :exec-hook exec-hook
            :live live :max-live max-live})
        (finally
          (pool/close! p)
          (ledger/close! db))))))

(defn new-harness
  "Plane + runner + RAP server wired so every RAP invoke runs through the gate."
  [h budget]
  (let [pl (plane/new-plane {:budget budget
                             :policy-set {:policies egress-policy :schema {} :entities []}})
        runner (plane/new-runner {:plane pl :pool (:pool h) :fanout (:fanout h)
                                  :exec-hook (:exec-hook h)
                                  :egress {"egr_clean" clean-egress "egr_bad" forbidden-egress}})
        srv (rap/new-server {:fanout (:fanout h)
                             :run-hook (partial plane/run-sandbox! runner)})]
    {:plane pl :runner runner :srv srv}))

(defn run-calls
  "Drive n RAP tool invocations concurrently; returns the terminal frames."
  [srv calls]
  ;; warm the JDK classes fanout/rap touch so the futures don't race on first use
  (str (java.time.Instant/now))
  (str (java.util.UUID/randomUUID))
  (mapv deref (mapv (fn [c] (future (invoke-sync srv c))) calls)))

;; -- N sandboxes over RAP under budget ---------------------------------------

(with-e2e
  (fn [h]
    (let [{:keys [plane srv]} (new-harness h {:cpu 2 :mem-mb 1024})]
      (try
        (let [results (run-calls srv (mapv #(tool-call %) (range 6)))]
          (check "6 RAP invocations all reach an ok terminal"
                 ["ok" "ok" "ok" "ok" "ok" "ok"] (mapv :status results))
          (check "every terminal carries exit-code 0"
                 [0 0 0 0 0 0] (mapv :exit-code results))
          (check "every terminal carries a stdout segment"
                 true (every? #(re-find #"ok-" (get-in % [:segments 0 :content])) results))
          (check "budget bounds live sandboxes at 2" 2 @(:max-live h))
          (check "budget drains after the run" {:cpu 0 :mem-mb 0} (:used (plane/stats plane))))
        (finally (plane/close! plane))))))

;; -- policy deny + unknown egress over RAP -----------------------------------

(with-e2e
  (fn [h]
    (let [{:keys [plane srv]} (new-harness h {:cpu 4 :mem-mb 4096})]
      (try
        (let [results (run-calls srv
                       [(tool-call 0)
                        (tool-call 1 {:egress forbidden-egress})
                        (tool-call 2 {:egress {:ref "egr_bad"}})
                        (tool-call 3 {:egress {:ref "egr_missing"}})])]
          (check "clean ok; forbidden/ref denied; unknown ref error"
                 ["ok" "error" "error" "error"] (mapv :status results))
          (check "inline forbidden terminal carries denied code"
                 "denied" (get-in results [1 :error :code]))
          (check "ref forbidden terminal carries denied code"
                 "denied" (get-in results [2 :error :code]))
          (check "unknown egress ref terminal carries egress-unknown code"
                 "egress-unknown" (get-in results [3 :error :code]))
          (check "denies/errors charge no budget" {:cpu 0 :mem-mb 0} (:used (plane/stats plane))))
        (finally (plane/close! plane))))))

(if (zero? @failures)
  (println "jolt-plane e2e (RAP through the gate): all green")
  (do (println (str @failures " FAILURES"))
      (System/exit 1)))
