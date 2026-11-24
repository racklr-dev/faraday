;; faraday.console.views.glimmer — the dashboard in glimmer-widget vocabulary
;; for the terminal client. One section per browser tab; two entry points share
;; them: app (direct glimmer-tui, :view ratom) and live-region (datastar EDN,
;; $view-gated).
(ns faraday.console.views.glimmer
  (:require [clojure.string :as str]))

(def nav-items
  [["overview" "Overview"] ["runs" "Runs"] ["agents" "Agents"]
   ["threads" "Threads"] ["vms" "VMs"] ["policy" "Policy"]
   ["egress" "Egress"] ["bench" "Bench"]])

(defn- run-line [r]
  (str "  " (:agent r) "  " (:tool r) "  "
       (subs (str (:argv r)) 0 (min 52 (count (str (:argv r)))))
       "  → " (:status r)))

(defn overview-section [b]
  (let [{:keys [plane pool runs gate]} b]
    [:vbox {:spacing 0}
     [:label {:label (str " plane   cpu " (get-in plane [:used :cpu]) "/" (get-in plane [:budget :cpu])
                          "   mem " (get-in plane [:used :mem-mb]) "/" (get-in plane [:budget :mem-mb])
                          "   granted " (:granted plane) "   waiting " (:waiting plane))}]
     [:label {:label (str " pool    warm " (:warm pool) "/" (:total pool)
                          "   snapshots " (:snapshots pool))}]
     [:label {:label (str " gate    " (count gate) " decisions   runs " (count runs))}]]))

(defn runs-section [b]
  (let [runs (:runs b)]
    [:frame {:label " runs " :border :rounded :vexpand true :margin [0 1]}
     [:scroll {:orientation :vertical :vexpand true}
      (into [:vbox {:spacing 0}]
            (if (empty? runs)
              [[:label {:label "(no runs yet)" :dim true}]]
              (mapv (fn [r] [:label {:label (run-line r)}]) runs)))]]))

(defn agents-section [b]
  (let [agents (set (map :owner (:runs b)))]
    [:vbox {:spacing 0}
     (into [:vbox {:spacing 0}]
           (if (empty? agents)
             [[:label {:label "(no agents yet)" :dim true}]]
             (mapv (fn [a] [:label {:label (str "  " a) :bold true}]) agents)))]))

(defn threads-section [b]
  (let [calls (:calls b)
        grouped (group-by :thread-id calls)
        detail (:thread-detail b)
        msg-line (fn [m]
                   (let [p (pr-str (or (:payload m) ""))]
                     (str "  " (or (:kind m) "") " " (subs p 0 (min 60 (count p))))))]
    [:vbox {:spacing 0}
     (into [:vbox {:spacing 0}]
           (if (empty? grouped)
             [[:label {:label "(no threads yet)" :dim true}]]
             (mapv (fn [[tid rs]]
                     [:label {:label (str "  " tid "  (" (count rs) ")  " (:agent (first rs)))}])
                   grouped)))
     (when detail
       [:frame {:label (str " thread " (:thread-id detail) " ") :border :rounded :vexpand true :margin [0 1]}
        [:scroll {:orientation :vertical :vexpand true}
         (into [:vbox {:spacing 0}]
               (mapv (fn [m] [:label {:label (msg-line m)}]) (:msgs detail)))]])]))

(defn vms-section [b]
  (let [pool (:pool b)]
    [:vbox {:spacing 0}
     [:label {:label (str " pool: warm " (:warm pool) " / total " (:total pool)
                          "   snapshots " (:snapshots pool))}]
     (into [:vbox {:spacing 0}]
           (if (empty? (:sandboxes pool))
             [[:label {:label "(no sandboxes)" :dim true}]]
             (mapv (fn [s] [:label {:label (str "  " (:id s) "  " (:state s))}]) (:sandboxes pool))))]))

(defn gate-section [b]
  (let [gate (:gate b)]
    [:frame {:label " gate decisions " :border :rounded :vexpand true :margin [0 1]}
     [:scroll {:orientation :vertical :vexpand true}
      (into [:vbox {:spacing 0}]
            (if (empty? gate)
              [[:label {:label "(no decisions yet)" :dim true}]]
              (mapv (fn [g] [:label {:label (str "  " (:principal g) "  " (:action g)
                                                 "  " (:resource g) "  → " (:decision g))}])
                    gate)))]]))

(defn policy-section [b]
  (let [ps (:policies b)]
    [:frame {:label " cedar policies " :border :rounded :vexpand true :margin [0 1]}
     [:scroll {:orientation :vertical :vexpand true}
      (into [:vbox {:spacing 0}]
            (if (empty? ps)
              [[:label {:label "(no policies)" :dim true}]]
              (mapv (fn [p] [:label {:label (str "  " (subs p 0 (min 64 (count p))))}]) ps)))]]))

(defn egress-section [b]
  (let [es (:egress b)]
    [:vbox {:spacing 0}
     (into [:vbox {:spacing 0}]
           (if (empty? es)
             [[:label {:label "(no egress configs)" :dim true}]]
             (mapv (fn [e] [:label {:label (str "  " (:egress-id e) "  (default " (or (:default e) "deny") ")")}]) es)))]))

(defn bench-section [b]
  (let [bench (:bench b)]
    [:vbox {:spacing 0}
     (if bench
       [:label {:label (str "  benchmark updated " (:updated-ms bench)
                            "  — " (count (or (:waves bench) [])) " waves")}]
       [:label {:label "(no benchmark results yet)" :dim true}])]))

(defn app
  "The direct glimmer-tui console: a :view ratom, tab buttons with :on-click,
  and the active section only."
  [b view]
  [:vbox {:spacing 0}
   [:label {:label " faraday console " :bold true :color :bright-cyan}]
   [:hbox {:spacing 1 :margin [0 1]}
    (for [[k label] nav-items]
      [:button {:label label :on-click #(reset! view k)}])]
   (case @view
     "overview" (overview-section b)
     "runs" (runs-section b)
     "agents" (agents-section b)
     "threads" (threads-section b)
     "vms" (vms-section b)
     "policy" (policy-section b)
     "egress" (egress-section b)
     "bench" (bench-section b)
     [:label {:label "unknown view"}])])

(defn live-region
  "The datastar EDN hiccup (all sections, gated by client-side $view)."
  [b]
  [:vbox {:id "app" :spacing 0}
   [:label {:label " faraday console " :bold true :color :bright-cyan}]
   [:hbox {:spacing 1 :margin [0 1]}
    (for [[k label] nav-items]
      [:button {:label label :data-on:click (str "$view = '" k "'")}])]
   (for [[k _] nav-items]
     (case k
       "overview" (with-meta (overview-section b) {:data-show "$view == 'overview'"})
       "runs" (with-meta (runs-section b) {:data-show "$view == 'runs'"})
       "agents" (with-meta (agents-section b) {:data-show "$view == 'agents'"})
       "threads" (with-meta (threads-section b) {:data-show "$view == 'threads'"})
       "vms" (with-meta (vms-section b) {:data-show "$view == 'vms'"})
       "policy" (with-meta (policy-section b) {:data-show "$view == 'policy'"})
       "egress" (with-meta (egress-section b) {:data-show "$view == 'egress'"})
       "bench" (with-meta (bench-section b) {:data-show "$view == 'bench'"})))])
