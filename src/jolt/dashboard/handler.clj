;; The ring handler: one route (/) that serves the full page on a normal
;; GET and the live fragment on the SSE request wrap-datastar opens, plus the
;; vendored Datastar/Rocket bundle.
(ns jolt.dashboard.handler
  (:require [clojure.java.io :as io]
            [jolt.datastar.core :as ds]
            [jolt.datastar.html :as h]
            [jolt.dashboard.board :as board]
            [jolt.dashboard.views :as views]))

(defonce datastar-js
  (delay (slurp (io/resource "datastar-rocket.js"))))

(defn- page
  "The full document: datastar script + the live region inline, with init-opts
  on <body> so the client opens the SSE stream against the same path."
  [b]
  (h/html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "jolt dashboard"]
     [:script {:src "/assets/datastar-rocket.js" :type "module"}]]
    [:body (ds/init-opts {:signals {}})
     (views/live-region b)]]))

(defn handler
  "App handler. On an SSE request (jolt.datastar/sse-request) return only the
  live-region fragment; wrap-datastar streams it and patches <body> inner."
  [req]
  (if (:jolt.datastar/sse-request req)
    {:status 200 :body (h/html (views/live-region @board/board))}
    (case (:uri req)
      "/"
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (page @board/board)}

      "/assets/datastar-rocket.js"
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=utf-8"
                 "Cache-Control" "public, max-age=86400"}
       :body @datastar-js}

      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "not found"})))
