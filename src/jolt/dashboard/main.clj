;; jolt-dashboard entrypoint: open a plane, sample it into the board ratom,
;; and serve the Datastar UI over ring-chez-adapter.
(ns jolt.dashboard.main
  (:require [jolt.plane :as plane]
            [jolt.datastar.core :as ds]
            [jolt.dashboard.board :as board]
            [jolt.dashboard.handler :as handler]
            [ring-chez.adapter :as adapter]
            [ring-chez.middleware.gzip :as gzip]))

(def demo-config
  "A permit-all plane with a small budget, so the gauge shows real stats."
  {:budget {:cpu 4 :mem-mb 4096}
   :weights {:cpu 1 :mem-mb 1}
   :policy-set {:policies ["permit(principal, action, resource);"]
                :schema {}
                :entities []}})

(defn -main [& _]
  (let [pl (plane/new-plane demo-config)
        stop-sampler (board/start-sampler! {:plane pl})
        app (-> handler/handler
                (ds/wrap-datastar {:tick-ms 15})
                gzip/wrap-gzip)
        _server (adapter/run-server app {:port 8787 :strategy :fibers})]
    (println "jolt dashboard: http://127.0.0.1:8787")
    (println "plane stats:" (pr-str (plane/stats pl)))
    ;; park main so the acceptor fibers stay alive (sampler + server run on
    ;; their own threads; stop-sampler is here for a future SIGTERM hook)
    @(promise)))
