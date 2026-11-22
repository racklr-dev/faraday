;; jolt-plane wiring (nc-FF99): N sandboxes under budget with policy egress.
;; No KVM: the VM boundary (start!/stop!) is faked via with-redefs and the
;; exec hook is injected, so this runs with plain jolt run.
;; Run: cd jolt-plane && JOLT_CEDAR_LIB=<libcedar_policy.so> jolt run test/jolt/plane_wiring_test.clj
(ns jolt.test.plane-wiring
  (:require [jolt.plane :as plane]
            [jolt.vm-pool :as pool]
            [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]
            [jolt.microvm :as microvm]))

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

(defn sandbox-req [i & {:as overrides}]
  (merge {:v 1
          :request-id (str "req_" i)
          :image image
          :argv ["/bin/echo" (str "sandbox-" i)]
          :cpu 1
          :mem-mb 512
          :egress {:ref "egr_clean"}}
         overrides))

(defn with-wiring [f]
  (let [root (str "/tmp/jolt-plane-wiring-" (System/nanoTime))
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

;; -- N sandboxes under budget -------------------------------------------------

(with-wiring
  (fn [h]
    (let [plane (plane/new-plane {:budget {:cpu 2 :mem-mb 1024}
                                  :policy-set {:policies egress-policy :schema {} :entities []}})
          runner (plane/new-runner {:plane plane :pool (:pool h) :fanout (:fanout h)
                                    :exec-hook (:exec-hook h)
                                    :egress {"egr_clean" clean-egress "egr_bad" forbidden-egress}})]
      (try
        (let [results (plane/run-all! runner (mapv #(sandbox-req %) (range 6)))]
          (check "6 sandboxes all complete ok"
                 [:ok :ok :ok :ok :ok :ok] (mapv :status results))
          (check "budget bounds live sandboxes at 2" 2 @(:max-live h))
          (check "budget drains after the run" {:cpu 0 :mem-mb 0} (:used (plane/stats plane)))
          (check "no grants leak" 0 (:granted (plane/stats plane)))
          (check "each thread recorded spawn + report"
                 [2 2 2 2 2 2]
                 (sort (mapv (fn [r] (count (ledger/history (:ledger h) (:thread-id r)))) results)))
          (check "reports carry status ok"
                 ["ok" "ok" "ok" "ok" "ok" "ok"]
                 (mapv #(get-in % [:report :payload :status]) results)))
        (finally (plane/close! plane))))))

;; -- policy egress denies forbidden + unknown ref errors ----------------------

(with-wiring
  (fn [h]
    (let [plane (plane/new-plane {:budget {:cpu 4 :mem-mb 4096}
                                  :policy-set {:policies egress-policy :schema {} :entities []}})
          runner (plane/new-runner {:plane plane :pool (:pool h) :fanout (:fanout h)
                                    :exec-hook (:exec-hook h)
                                    :egress {"egr_clean" clean-egress "egr_bad" forbidden-egress}})]
      (try
        (let [reqs [(sandbox-req 1)
                    (sandbox-req 2 :egress forbidden-egress)
                    (sandbox-req 3 :egress {:ref "egr_bad"})
                    (sandbox-req 4 :egress {:ref "egr_missing"})]
              results (plane/run-all! runner reqs)]
          (check "clean allows; forbidden denies; unknown ref errors"
                 [:ok :denied :denied :error] (mapv :status results))
          (check "inline forbidden carries :policy-deny"
                 :policy-deny (get-in results [1 :type]))
          (check "ref forbidden carries :policy-deny"
                 :policy-deny (get-in results [2 :type]))
          (check "unknown egress ref carries :egress-unknown"
                 :egress-unknown (get-in results [3 :type]))
          (check "denies/errors charge no budget" {:cpu 0 :mem-mb 0} (:used (plane/stats plane)))
          (check "denied/error runs spawned no thread"
                 [nil nil nil] (mapv :thread-id (subvec results 1)))
          (check "allowed run reported ok" "ok" (get-in (first results) [:report :payload :status])))
        (finally (plane/close! plane))))))

(if (zero? @failures)
  (println "jolt-plane wiring: all green")
  (do (println (str @failures " FAILURES"))
      (System/exit 1)))
