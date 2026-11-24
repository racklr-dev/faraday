;; faraday.harness.console — the operator console: chat + dashboard modes over
;; one spine. The chat is faraday.harness.chat's component; the dashboard modes
;; are faraday.console.views.glimmer's sections over faraday.console.board's
;; ratom. The spine is the board + the chat's tools/session; terminal and web
;; are two clients of it (this file is the terminal client).
(ns faraday.harness.console
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core]
            [faraday.console.board :as board]
            [faraday.console.runtime :as crt]
            [faraday.console.views.glimmer :as gv]
            [faraday.harness.chat :as chat]))

(def modes
  [["chat" "Chat"] ["overview" "Overview"] ["runs" "Runs"]
   ["agents" "Agents"] ["threads" "Threads"] ["vms" "VMs"]
   ["policy" "Policy"] ["egress" "Egress"] ["bench" "Bench"]])

(defn app
  "One tab bar over the modes; the chat mode embeds the chat component, the
  dashboard modes read the board ratom."
  [chat-opts view]
  [:vbox {:spacing 0}
   [:label {:label " faraday " :bold true :color :bright-cyan}]
   [:hbox {:spacing 1 :margin [0 1]}
    (for [[k label] modes]
      [:button {:label label :on-click #(reset! view k)}])]
   (case @view
     "chat" [(chat/chat-app chat-opts)]
     "overview" (gv/overview-section @board/board)
     "runs" (gv/runs-section @board/board)
     "agents" (gv/agents-section @board/board)
     "threads" (gv/threads-section @board/board)
     "vms" (gv/vms-section @board/board)
     "policy" (gv/policy-section @board/board)
     "egress" (gv/egress-section @board/board)
     "bench" (gv/bench-section @board/board)
     [:label {:label "unknown view"}])])

(defn run!
  "Open the console stack (board + sampler) and render the modes in the
  terminal, starting at `initial-view` (default chat). The chat's pool tools
  see the same pool handle via `pool-ref`."
  [{:keys [chat-opts initial-view pool-ref] :or {initial-view "chat"}}]
  (let [runtime (crt/open-runtime! {:on-run board/record-run!})]
    (when pool-ref (reset! pool-ref (:pool runtime)))
    (board/seed-config! runtime)
    (board/sample! runtime)
    (board/start-sampler! runtime 500)
    (let [view (r/atom initial-view)]
      (ui/run (fn [] (app chat-opts view)) :title "faraday"))))
