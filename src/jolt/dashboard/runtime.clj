;; Open the full plane stack with a REAL KVM microvm boundary (jolt-microvm
;; + libkrun 2.0): plane + pool stats, gate decisions, runs through the RAP
;; run-hook (default plane exec = in-guest ssh exec), calls, threads,
;; policies, egress, and the actions submit-run!, cancel!, dry-run.
(ns jolt.dashboard.runtime
  (:require [clojure.string :as str]
            [jolt.plane :as plane]
            [jolt.vm-pool :as pool]
            [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]
            [jolt.rap :as rap]))

(def q (str (char 34)))

(def image "alpine:3.20")

(def policies
  "Identity-scoped: alice + bob may run; anything reaching 8.8.8.8/32 is
  forbidden (so egr_bad denies even for alice). A third agent is denied by
  default (no permit)."
  [(str "permit(principal, action, resource)"
        " when { action == Action::" q "RunSandbox" q " &&"
        "        principal == Agent::" q "alice" q " };")
   (str "permit(principal, action, resource)"
        " when { action == Action::" q "RunSandbox" q " &&"
        "        principal == Agent::" q "bob" q " };")
   (str "forbid(principal, action, resource)"
        " when { action == Action::" q "RunSandbox" q " &&"
        "        resource.egress has allow_cidrs &&"
        "        resource.egress.allow_cidrs.containsAny([" q "8.8.8.8/32" q "]) };")])

(def egresses
  {"egr_public" {:egress-id "egr_public" :dns ["1.1.1.1"] :allow-cidrs ["0.0.0.0/0"]
                 :host-gateway true :default "deny"}
   "egr_clean"  {:egress-id "egr_clean" :dns ["1.1.1.1"] :allow-cidrs ["1.1.1.1/32"]
                 :host-gateway true :default "deny"}
   "egr_bad"    {:egress-id "egr_bad" :dns ["1.1.1.1"] :allow-cidrs ["1.1.1.1/32" "8.8.8.8/32"]
                 :host-gateway true :default "deny"}})

(defn- ->cedar-egress [e]
  (when e
    (->> {:egress_id (:egress-id e) :dns (:dns e)
          :allow_cidrs (:allow-cidrs e) :deny_cidrs (:deny-cidrs e)
          :host_gateway (:host-gateway e) :default (:default e)}
         (remove (fn [[_ v]] (nil? v)))
         (into {}))))

(defn open-runtime!
  "Open the plane stack. opts: :on-run (fn call result) fires when a run
  completes through the RAP run-hook, with the full run-sandbox! result."
  [opts]
  (let [root (str "/tmp/jolt-dashboard-" (System/nanoTime))
        db (ledger/new-ledger {:path (str root "/ledger.db")})
        fan (fanout/new-runtime {:ledger db})
        pl (pool/new-pool {:image image :cache-dir "/home/jm/.cache/jolt-microvm" :max-warm 4})
        plane (plane/new-plane {:budget {:cpu 4 :mem-mb 4096}
                                :weights {:cpu 1 :mem-mb 1}
                                :policy-set {:policies policies :schema {} :entities []}})
        calls (atom {})
        ;; default plane exec-hook runs argv in-guest over SSH (microvm/exec);
        ;; no fake boundary.
        runner (plane/new-runner {:plane plane :pool pl :fanout fan
                                  :egress egresses})
        run-hook (fn [request]
                   (let [info (get @calls (:request-id request))
                         request (cond-> request
                                   (:owner info) (assoc :owner (:owner info))
                                   (:parent-id info) (assoc :parent-id (:parent-id info)))
                         result (plane/run-sandbox! runner request)]
                     (when-let [cb (:on-run opts)] (cb info result))
                     result))
        srv (rap/new-server {:fanout fan :run-hook run-hook})]
    {:plane plane :pool pl :fanout fan :ledger db :runner runner :rap srv
     :root root :calls calls :policies policies :egress egresses :image image}))

;; -- read adapters ------------------------------------------------------------

(defn plane-stats [rt] (try (plane/stats (:plane rt)) (catch Exception _ {})))

(defn pool-stats [rt]
  (try (pool/stats (:pool rt)) (catch Exception _ {:warm 0 :total 0 :snapshots 0})))

(defn list-sandboxes [rt]
  (try
    (let [s @(:state (:pool rt))
          hs (concat (map #(assoc (:handle %) :st :warm) (:warm s))
                     (map #(assoc (val %) :st :busy) (:busy s)))]
      (mapv (fn [h] {:id (:id h) :image (:image h) :state (name (:st h))}) hs))
    (catch Exception _ [])))

(defn list-calls [rt]
  (try
    (->> (vals (get-in @(:state (:rap rt)) [:calls]))
         (mapv (fn [c]
                 (let [call (:call c) t (:terminal c)]
                   {:call-id (:call-id call)
                    :thread-id (:thread-id call)
                    :name (:name call)
                    :display-as (or (:display-as call)
                                    (str/join " " (get-in call [:args :argv])))
                    :status (or (:status t) "running")
                    :exit-code (:exit-code t)
                    :error (:error t)
                    :segments (:segments t)})))
         (sort-by :call-id))
    (catch Exception _ [])))

(defn history [rt thread-id]
  (try (vec (ledger/history (:ledger rt) thread-id)) (catch Exception _ [])))

;; -- actions ------------------------------------------------------------------

(defn submit-run! [rt call]
  "Queue a RAP tool invocation; returns the call-id (runs async)."
  (swap! (:calls rt) assoc (:call-id call) call)
  (rap/invoke (:rap rt) call))

(defn cancel! [rt call-id]
  (rap/cancel (:rap rt) call-id))

(defn dry-run [rt principal action image egress-ref]
  "The policy simulator: the cedar decision for a synthetic request, without
  admitting or running it."
  (let [eg (get (:egress rt) egress-ref)
        sid "dry"
        req {:request-id "dry"
             :principal {:type "Agent" :id principal}
             :action {:type "Action" :id action}
             :resource {:type "Sandbox" :id sid}
             :context {:request-id "dry"}
             :request-entities
             (when eg
               [{:uid {:type "Sandbox" :id sid}
                 :attrs (cond-> {:image image}
                          eg (assoc :egress (->cedar-egress eg)))
                 :parents []}])}]
    {:decision (plane/decide (:plane rt) req)
     :principal principal :action action :image image :egress egress-ref}))
