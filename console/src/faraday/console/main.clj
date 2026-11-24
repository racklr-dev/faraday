;; faraday-console entrypoint: open the real plane stack (real KVM boundary), sample it
;; into the board ratom, drive a rotating demo through the RAP run-hook so
;; every view has live data, and serve the Datastar UI over ring-chez-adapter.
(ns faraday.console.main
  (:require [jolt.datastar.core :as ds]
            [faraday.console.board :as board]
            [faraday.console.handler :as handler]
            [faraday.console.runtime :as rt]
            [faraday.console.tui :as tui]
            [ring-chez.adapter :as adapter]
            [ring-chez.middleware.gzip :as gzip]))

(def scenarios
  "Rotating demo: two permits, an egress forbid, an identity deny, an error."
  [{:owner "alice"   :name "run_shell" :display-as "echo hi"
    :args {:argv ["/bin/echo" "hi from alice"] :cpu 1 :mem-mb 512 :egress {:ref "egr_clean"}}}
   {:owner "bob"     :name "run_shell" :display-as "fetch public"
    :args {:argv ["/bin/sh" "-c" "echo fetching over public egress"] :cpu 1 :mem-mb 512 :egress {:ref "egr_public"}}}
   {:owner "alice"   :name "run_shell" :display-as "bad egress (denied)"
    :args {:argv ["/bin/sh" "-c" "echo should-not-run"] :cpu 1 :mem-mb 512 :egress {:ref "egr_bad"}}}
   {:owner "mallory" :name "run_shell" :display-as "intrude (denied)"
    :args {:argv ["/bin/sh" "-c" "whoami"] :cpu 1 :mem-mb 512 :egress {:ref "egr_clean"}}}
   {:owner "bob"     :name "run_tests" :display-as "failing test"
    :args {:argv ["/bin/sh" "-c" "fail now"] :cpu 1 :mem-mb 512 :egress {:ref "egr_clean"}}}])

(defn start-demo!
  "Drive a scenario every interval-ms through the real RAP run-hook; each
  terminal frame lands in the board via record-run!."
  [runtime interval-ms]
  (let [stop? (atom false) n (atom 0)]
    (future
      (loop []
        (when-not @stop?
          (try
            (let [s (nth scenarios (mod @n (count scenarios)))
                  call (assoc s :v 1
                                :call-id (str "call_" (System/nanoTime))
                                :thread-id (str "agent-" (:owner s)))]
              (rt/submit-run! runtime call))
            (catch Exception _ nil))
          (swap! n inc)
          (Thread/sleep interval-ms)
          (recur))))
    (fn [] (reset! stop? true))))

(defn -main [& args]
  (if (some #{"tui" "--tui"} args)
    (tui/run!)
    (let [runtime (rt/open-runtime! {:on-run board/record-run!
                                     :on-fault board/record-fault!})]
    (reset! handler/runtime runtime)
    (board/seed-config! runtime)
    (board/sample! runtime)
    (start-demo! runtime 2500)
    (board/start-sampler! runtime 500)
    (let [app (-> handler/handler
                  (ds/wrap-datastar {:tick-ms 15
                                     :namespace (fn [req] (if (handler/wants-edn? req) "edn" "html"))})
                  gzip/wrap-gzip)
          _server (adapter/run-server app {:port 8787 :strategy :fibers})]
      (println "jolt dashboard: http://127.0.0.1:8787")
      (println "plane stats:" (pr-str (rt/plane-stats runtime)))
      @(promise)))))
