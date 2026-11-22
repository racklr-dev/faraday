;; jolt-plane smoke: the gate wires budget + policy.
;; Run: cd jolt-plane && JOLT_CEDAR_LIB=<libcedar_policy.so path> jolt run test/jolt/plane_test.clj
(ns jolt.test.plane
  (:require [jolt.plane :as plane]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn denied? [e]
  (boolean (and (instance? Exception e)
                (re-find #"deny|denied|exceeds|closed|timeout" (ex-message e)))))

(defn req [principal-id cpu]
  {:request-id (str "req_" principal-id)
   :principal {:type "Agent" :id principal-id}
   :action {:type "Action" :id "exec"}
   :resource {:type "Image" :id "oci://example/distroless@sha256:abc"}
   :context {:egress {:dns ["1.1.1.1"] :allow-cidrs ["1.1.1.1/32"]
                      :host-gateway true :default "deny"}}
   :owner (str "thr_" principal-id)
   :cost {:cpu cpu :mem-mb 512}
   :priority 0
   :timeout-ms 0})

;; a double-quote char, so cedar entity names can be written without escapes
(def q (str (char 34)))

;; permit-all plane
(let [plane (plane/new-plane {:budget {:cpu 2 :mem-mb 1024}
                              :policy-set {:policies ["permit(principal, action, resource);"]
                                           :schema {}
                                           :entities []}})]
  (check "new-plane returns a handle" true (map? plane))
  (check "plane carries a cedar authorizer" true (map? (:authorizer plane)))
  (let [r (plane/gate plane (req "alice" 1))]
    (check "permit-all gate decides allow" :allow (:decision r))
    (check "permit-all gate grants a permit" "granted" (get-in r [:permit :status]))
    (check "grant charges the budget" {:cpu 1 :mem-mb 512} (:used (plane/stats plane)))
    (let [rel (plane/release plane (:permit r))]
      (check "release returns released" "released" (:status rel))
      (check "release drains the budget" {:cpu 0 :mem-mb 0} (:used (plane/stats plane)))))
  (plane/close! plane))

;; scoped policy: the cedar engine decides by principal identity
(let [alice-only (str "permit(principal == Agent::" q "alice" q ", action, resource);")
      plane (plane/new-plane {:budget {:cpu 2 :mem-mb 1024}
                              :policy-set {:policies [alice-only]
                                           :schema {}
                                           :entities []}})]
  (check "scoped policy allows alice" :allow (:decision (plane/gate plane (req "alice" 1))))
  (let [e (try (plane/gate plane (req "bob" 1)) ::no-throw (catch Exception ex ex))]
    (check "scoped policy denies bob" true (denied? e))
    (check "deny carries :policy-deny" :policy-deny (:type (ex-data e))))
  (plane/close! plane))

;; budget still binds after policy: over budget times out
(let [plane (plane/new-plane {:budget {:cpu 1 :mem-mb 512}
                              :policy-set {:policies ["permit(principal, action, resource);"]
                                           :schema {}
                                           :entities []}})]
  (plane/gate plane (req "alice" 1))
  (let [e (try (plane/gate plane (assoc (req "eve" 1) :timeout-ms 120)) ::no-throw
               (catch Exception ex ex))]
    (check "over budget is denied" true (denied? e)))
  (plane/close! plane))

;; close! contract
(let [plane (plane/new-plane {:budget {:cpu 2 :mem-mb 1024}
                              :policy-set {:policies ["permit(principal, action, resource);"]
                                           :schema {}
                                           :entities []}})]
  (check "close! is idempotent" nil (plane/close! plane))
  (check "gate after close! throws"
         true (try (plane/gate plane (req "alice" 1)) false
                   (catch Exception e (boolean (re-find #"closed" (ex-message e)))))))

(println "jolt-plane smoke: all green")
