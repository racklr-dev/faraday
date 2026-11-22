;; Hiccup fragments for the dashboard, rendered by jolt.datastar.html/html.
;; Each panel is a component (a vector headed by a function) whose root has
;; an id, so glimmer-datastar's part diff sends only the panel that changed.
(ns jolt.dashboard.views
  (:require [jolt.datastar.html :as h]))

(defn budget-panel
  "Live admission stats: budget/used per resource, granted + waiting counts."
  [stats]
  (let [{:keys [budget used granted waiting]} stats]
    [:section {:id "budget"}
     [:h2 "Budget"]
     [:table
      [:thead [:tr [:th "resource"] [:th "budget"] [:th "used"]]]
      [:tbody
       (for [[k v] (sort-by key (or budget {}))]
         [:tr {:id (str "budget-" (name k))}
          [:td (name k)]
          [:td (str v)]
          [:td (str (get used k 0))]])]]
     [:p "granted: " [:span {:id "granted"} (str (or granted 0))]
      "  waiting: " [:span {:id "waiting"} (str (or waiting 0))]]]))

(defn pool-panel
  "Warm/total VM counts (wired to jolt-vm-pool in M1; 0/0 until then)."
  [pool]
  [:section {:id "pool"}
   [:h2 "Pool"]
   [:p [:span {:id "pool-warm"} (str (:warm pool))] " warm / "
    [:span {:id "pool-total"} (str (:total pool))] " total"]])

(defn live-region
  "The fragment that the SSE stream re-renders and patches into <body>."
  [b]
  [:div
   [:header
    [:h1 "jolt plane"]
    [:p "last sample: " [:span {:id "hb"} (str (:heartbeat b))]]]
   [budget-panel (:plane b)]
   [pool-panel (:pool b)]])
