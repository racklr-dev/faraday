;; faraday.harness.console — the operator console. The terminal client of the
;; spine is the chat surface; the dashboard modes are the web gateway's client,
;; which run! starts below. Terminal and web are two clients of one spine
;; (board + chat tools/session), so the terminal spends its screen on the
;; conversation rather than on chrome.
(ns faraday.harness.console
  (:require [glimmer.core :as ui]
            [glimmer-tui.core]
            [jolt.datastar.core :as datastar]
            [faraday.console.board :as board]
            [faraday.console.handler :as handler]
            [faraday.console.runtime :as crt]
            [faraday.harness.chat :as chat]
            [ring-chez.adapter :as adapter]
            [clojure.string :as str]))

(defn app
  "The terminal surface: the chat component. The dashboard is served by the web
  gateway started in run!."
  [chat-opts]
  (chat/chat-app chat-opts))

(defn- start-gateway!
  "Start the web gateway on `port` if nothing is listening there; return true if
  this process started it, false if one is already running."
  [runtime port]
  (reset! handler/runtime runtime)
  (let [app (datastar/wrap-datastar handler/handler {:tick-ms 15})]
    (try
      (adapter/run-server app {:port port :strategy :fibers})
      (println (str "gateway: http://127.0.0.1:" port))
      true
      (catch Exception e
        (if (str/includes? (str (ex-message e)) "already in use")
          (do (println (str "gateway already running on :" port)) false)
          (throw e))))))

(defn run!
  "Open the console stack (board + sampler), start the web gateway if it is not
  running, and render the chat in the terminal. The chat's pool tools see the
  same pool handle via `pool-ref`."
  [{:keys [chat-opts pool-ref port] :or {port 8787}}]
  (let [runtime (crt/open-runtime! {:on-run board/record-run!})]
    (when pool-ref (reset! pool-ref (:pool runtime)))
    (board/seed-config! runtime)
    (board/sample! runtime)
    (board/start-sampler! runtime 500)
    (start-gateway! runtime port)
    ;; Run the TUI on the terminal's alternate screen: the transcript stays
    ;; off the shell's scrollback and, on ctrl-c, the operator's shell is
    ;; restored as it was instead of leaving the transcript on screen.
    (print "\u001b[?1049h") (flush)
    (try
      (ui/run (chat/chat-app chat-opts) :title "faraday" :mouse? false)
      (finally
        (print "\u001b[?1049l") (flush)))))
