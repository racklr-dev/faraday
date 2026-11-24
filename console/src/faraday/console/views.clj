;; Hiccup fragments for the dashboard, rendered by jolt.datastar.html/html.
;; Each view section is gated by data-show on the client-side $view signal, so
;; nav switching is instant (no round-trip); the SSE stream patches the live
;; data inside whichever view is open.
(ns faraday.console.views
  (:require [clojure.string :as str]
            [jolt.datastar.html :as h]))

(def nav-items
  [["overview" "Overview"] ["runs" "Runs"] ["agents" "Agents"]
   ["threads" "Threads"] ["vms" "VMs"] ["policy" "Policy"]
   ["egress" "Egress"]
   ["bench" "Benchmarks"]])

(declare dry-result thread-detail dry-btn)

(defn- pct [used budget]
  (if (and budget (pos? budget)) (min 100 (int (* 100.0 (/ used budget)))) 0))

(defn gauge
  "A labelled progress bar for a resource (cpu/mem)."
  [label used budget unit]
  (let [p (pct used budget)]
    [:div {:class "gauge"}
     [:span {:class "gauge-label"} label]
     [:div {:class "gauge-bar"}
      [:div {:class "gauge-fill" :style {:width (str p "%")}}]]
     [:span {:class "gauge-num"} (str used "/" budget " " unit)]]))

(defn shell
  "Persistent chrome: compact budget strip + the nav rail."
  [b]
  (let [pl (:plane b)]
    [:div
     [:header {:id "topbar"}
      [:span {:class "brand"} "jolt plane"]
      [:span {:id "tb-stats"}
       (str "cpu " (get-in pl [:used :cpu] 0) "/" (get-in pl [:budget :cpu] 0)
            "  ·  mem " (get-in pl [:used :mem-mb] 0) "/" (get-in pl [:budget :mem-mb] 0)
            " MiB  ·  granted " (or (:granted pl) 0) "  ·  waiting " (or (:waiting pl) 0))]
      (when (:notice b)
        [:span {:id "notice" :class "notice"} (:notice b)])]
     [:nav {:id "nav"}
      (for [[k label] nav-items]
        [:button {:id (str "nav-" k)
                  :data-on:click (str "$view = '" k "'")}
         label])]]))

(defn overview-panel [b]
  (let [pl (:plane b) pool (:pool b) gate (:gate b) calls (:calls b) runs (:runs b)
        live (filter #(= "running" (:status %)) calls)]
    [:section {:id "view-overview" :class "view" :data-show "$view == 'overview'"}
     [:h2 "Overview"]
     [:div {:class "grid"}
      [:div {:class "card"}
       [:h3 "Budget"]
       [gauge "cpu" (get-in pl [:used :cpu] 0) (get-in pl [:budget :cpu] 0) "vcpu"]
       [gauge "mem" (get-in pl [:used :mem-mb] 0) (get-in pl [:budget :mem-mb] 0) "MiB"]]
      [:div {:class "card"}
       [:h3 "Pool"]
       [:p [:span {:id "pool-n"}
            (str (:warm pool) " warm / " (:total pool) " total / " (:snapshots pool) " snapshots")]]
       [:p [:code (:image b)]]]
      [:div {:class "card"}
       [:h3 "Gate — recent decisions"]
       [:table {:class "data"}
        [:thead [:tr [:th "req"] [:th "agent"] [:th "decision"]]]
        [:tbody
         (for [g (take 8 gate)]
           [:tr {:id (str "gate-" (:request-id g))}
            [:td (:request-id g)] [:td (:principal g)]
            [:td {:class (str "d " (:decision g))} (:decision g)]])]]]]
     [:div {:class "card"}
      [:h3 "Faults"]
      (if (seq (:faults b))
        (for [f (take 6 (:faults b))]
          [:p {:id (str "fault-" (:ts f))}
           (:agent f) " · " [:span {:class "d error"} (:error f)]])
        [:p "none — sandbox healthy"])]
     [:div {:class "card"}
      [:h3 "Live runs"]
      [:div {:class "row"}
       [dry-btn "▶ echo hi (alice)" "/run?agent=alice&argv=echo+hi"]
       [dry-btn "▶ uname -a (bob)" "/run?agent=bob&argv=uname+-a"]]
      (if (seq live)
        (for [c live]
          [:p {:id (str "live-" (:call-id c))} (:display-as c)])
        [:p "no runs in flight"])
      [:h3 "Recent activity"]
      (if (seq runs)
        (for [r (take 6 runs)]
          [:p {:id (str "act-" (:request-id r))}
           (:agent r) " · " (:tool r) " · "
           [:span {:class (str "d " (:status r))} (:status r)]])
        [:p "awaiting first run…"])]]))

(defn runs-panel [b]
  [:section {:id "view-runs" :class "view" :data-show "$view == 'runs'"}
   [:h2 "Runs"]
   [:table {:class "data"}
    [:thead [:tr [:th "req"] [:th "agent"] [:th "tool"] [:th "argv"]
                [:th "egress"] [:th "status"] [:th "exit"]]]
    [:tbody
     (for [r (:runs b)]
       [:tr {:id (str "run-" (:request-id r))}
        [:td (:request-id r)] [:td (:agent r)] [:td (:tool r)]
        [:td [:code (:argv r)]] [:td (:egress r)]
        [:td {:class (str "d " (:status r))} (:status r)]
        [:td (str (:exit r))]])]]
   [:div {:class "card"}
    [:h3 "stdout (latest run)"]
    (if-let [r (first (:runs b))]
      [:pre [:code (or (:stdout r) (or (:error r) ""))]]
      [:p "none"])]])

(defn agents-panel [b]
  (let [calls (:calls b)
        threads (->> calls
                     (group-by :thread-id)
                     (map (fn [[tid cs]]
                            {:id tid :n (count cs)
                             :live (boolean (some #(= "running" (:status %)) cs))}))
                     (sort-by :id))]
    [:section {:id "view-agents" :class "view" :data-show "$view == 'agents'"}
     [:h2 "Agents"]
     [:div {:class "grid"}
      [:div {:class "card"}
       [:h3 "Threads (agent identity)"]
       [:table {:class "data"}
        [:thead [:tr [:th "thread"] [:th "calls"] [:th "live"]]]
        [:tbody
         (for [a threads]
           [:tr {:id (str "agent-" (:id a))}
            [:td (:id a)] [:td (:n a)] [:td (if (:live a) "●" "—")]])]]]
      [:div {:class "card"}
       [:h3 "Invocations"]
       [:table {:class "data"}
        [:thead [:tr [:th "call"] [:th "tool"] [:th "display"] [:th "status"]
                    [:th "exit"] [:th ""]]]
        [:tbody
         (for [c calls]
           [:tr {:id (str "call-" (:call-id c))}
            [:td (:call-id c)] [:td (:name c)] [:td (:display-as c)]
            [:td {:class (str "d " (:status c))} (:status c)]
            [:td (str (:exit-code c))]
            [:td (when (= "running" (:status c))
                   [:button {:data-on:click
                             (str "@get('/cancel?call-id=" (:call-id c) "')")}
                    "cancel"])]])]]]]]))

(defn threads-panel [b]
  (let [threads (->> (:runs b)
                     (remove #(nil? (:thread-id %)))
                     (group-by :thread-id)
                     (map (fn [[tid rs]]
                            {:id tid :n (count rs) :agent (:agent (first rs))
                             :last (:status (first rs))}))
                     (sort-by :id))]
    [:section {:id "view-threads" :class "view" :data-show "$view == 'threads'"}
     [:h2 "Threads (ledger)"]
     [:table {:class "data"}
      [:thead [:tr [:th "thread"] [:th "agent"] [:th "runs"] [:th "last"]]]
      [:tbody
       (for [t threads]
         [:tr {:id (str "thread-" (:id t))}
          [:td [:button {:data-on:click (str "@get('/thread?thread-id=" (:id t) "')")}
                (:id t)]]
          [:td (:agent t)] [:td (:n t)] [:td (:last t)]])]]
     (if-let [td (:thread-detail b)]
       (thread-detail (:thread-id td) (:msgs td))
       [:div {:id "thread-detail" :class "card"}
        [:p "select a thread to see its messages"]])]))

(defn vms-panel [b]
  (let [pool (:pool b)]
    [:section {:id "view-vms" :class "view" :data-show "$view == 'vms'"}
     [:h2 "VMs"]
     [:div {:class "grid"}
      [:div {:class "card"}
       [:h3 "Pool"]
       [:p (str (:warm pool) " warm / " (:total pool) " total / "
                (:snapshots pool) " snapshots")]
       [:p [:code (:image b)]]]
      [:div {:class "card"}
       [:h3 "Sandboxes"]
       (if (seq (:sandboxes pool))
         [:table {:class "data"}
          [:thead [:tr [:th "id"] [:th "state"]]]
          [:tbody
           (for [s (:sandboxes pool)]
             [:tr {:id (str "sbx-" (:id s))}
              [:td (:id s)] [:td (:state s)]])]]
         [:p "none — a sandbox appears while a run is live"])]]]))

(defn dry-btn [label qs]
  [:button {:data-on:click (str "@get('" qs "')")}
   label])

(defn policy-panel [b]
  [:section {:id "view-policy" :class "view" :data-show "$view == 'policy'"}
   [:h2 "Policy (Cedar)"]
   [:div {:class "grid"}
    [:div {:class "card"}
     [:h3 "Policies"]
     (for [[i p] (map-indexed vector (:policies b))]
       [:pre {:id (str "policy-" i) :class "cedar"} p])]
    [:div {:class "card"}
     [:h3 "Simulate (dry-run)"]
     [:p "Ask the gate whether a run would be admitted — no sandbox is run."]
     [:div {:class "row"}
      [dry-btn "alice + clean" "/dry-run?principal=alice&egress=egr_clean"]
      [dry-btn "alice + bad egress" "/dry-run?principal=alice&egress=egr_bad"]
      [dry-btn "mallory + clean" "/dry-run?principal=mallory&egress=egr_clean"]
      [dry-btn "bob + public" "/dry-run?principal=bob&egress=egr_public"]]
     (if-let [d (:dry-result b)]
       (dry-result d)
       [:div {:id "dry-result"} [:p "pick a scenario —"]])]]])

(defn egress-panel [b]
  [:section {:id "view-egress" :class "view" :data-show "$view == 'egress'"}
   [:h2 "Egress"]
   [:table {:class "data"}
    [:thead [:tr [:th "id"] [:th "dns"] [:th "allow-cidrs"] [:th "deny-cidrs"]
                [:th "host-gw"]]]
    [:tbody
     (for [e (:egress b)]
       [:tr {:id (str "egress-" (:egress-id e))}
        [:td (:egress-id e)]
        [:td (str/join ", " (:dns e))]
        [:td (str/join ", " (:allow-cidrs e))]
        [:td (str/join ", " (or (:deny-cidrs e) []))]
        [:td (str (:host-gateway e))]])]]])

(defn- fmt1 [x]
  (when x (str (/ (long (Math/round (* 10.0 (double x)))) 10.0))))

(defn bench-panel [b]
  (let [bench (:bench b)
        waves (:waves bench)]
    [:section {:id "view-bench" :class "view" :data-show "$view == 'bench'"}
     [:h2 "Benchmarks (loadtest)"]
     (if (seq waves)
       [:div
        [:div {:class "card"}
         [:h3 "Run"]
         [:p [:span {:id "bench-run"}
              (str (:source bench) " · K=" (:k bench)
                   " · " (:image bench)
                   " · boot " (or (:boot-ms bench) "?") "ms"
                   (when (:done bench) " · done"))]]
         [:p [:span {:id "bench-updated"} (str "updated " (:updated-ms bench))]]]
        [:div {:class "card"}
         [:h3 "Waves (warm execs)"]
         [:table {:class "data"}
          [:thead [:tr [:th "conc"] [:th "n"] [:th "oks"] [:th "warm ms"]
                      [:th "exec/s"] [:th "per-VM/s"] [:th "avg ms"]]]
          [:tbody
           (for [[i w] (map-indexed vector waves)]
             [:tr {:id (str "bench-wave-" i)}
              [:td (:conc w)] [:td (:n w)] [:td (:oks w)]
              [:td (:warm-ms w)]
              [:td (fmt1 (:execs-per-sec w))]
              [:td (fmt1 (:per-vm-per-sec w))]
              [:td (fmt1 (:avg-latency-ms w))]])]]]]
       [:div {:id "bench-empty" :class "card"}
        [:p "no benchmark results yet — run the daemon-probe loadtest"]])
   ]))

(defn live-region
  "The fragment the SSE stream re-renders: chrome + every view section."
  [b]
  [:div
   [shell b]
   [overview-panel b]
   [runs-panel b]
   [agents-panel b]
   [threads-panel b]
   [vms-panel b]
   [policy-panel b]
   [egress-panel b]
   [bench-panel b]
   [:footer [:span {:id "foot"} (str "last sample " (:heartbeat b))]]])

;; -- action fragments ---------------------------------------------------------

(defn dry-result
  "Merged into #dry-result after a simulator action."
  [d]
  [:div {:id "dry-result"}
   [:p (str (:principal d) " · " (:action d) " · egress " (:egress d))]
   [:p {:class (str "decision " (name (:decision d)))}
    (str (str/upper-case (name (:decision d))))]])

(defn msg-summary [m]
  (case (:kind m)
    "report" (str "status=" (get-in m [:payload :status]))
    "spawn"  (str "argv=" (pr-str (get-in m [:payload :spec :argv])))
    (pr-str (select-keys (:payload m) [:status :event :from-thread-id :snapshot-ref]))))

(defn thread-detail
  "Merged into #thread-detail after a thread action."
  [tid msgs]
  [:div {:id "thread-detail"}
   [:h3 (str "thread " tid)]
   (if (seq msgs)
     [:table {:class "data"}
      [:thead [:tr [:th "seq"] [:th "kind"] [:th "payload"]]]
      [:tbody
       (for [m msgs]
         [:tr {:id (str "msg-" tid "-" (:seq m))}
          [:td (:seq m)] [:td (:kind m)] [:td [:code (msg-summary m)]]])]]
     [:p "no messages"])])
