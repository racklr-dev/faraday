;; faraday.console.tui — the terminal console: renders the board DIRECTLY via
;; glimmer-tui (no server, no SSE, no datastar-client). Tabs are a local :view
;; ratom and :on-click buttons, so switching is instant and headlessly testable
;; with glimmer-tui's buffer-screen. The browser console (jolt -M:serve) remains
;; the Datastar/HTML server; this is the in-process terminal half.
(ns faraday.console.tui
  (:require [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core]
            [faraday.console.board :as board]
            [faraday.console.runtime :as rt]
            [faraday.console.views.glimmer :as gv]))

(defn run!
  "Open the console stack (KVM-lazy) and draw the board in the terminal."
  [& _]
  (let [runtime (rt/open-runtime! {:on-run board/record-run!})]
    (board/seed-config! runtime)
    (board/sample! runtime)
    (board/start-sampler! runtime 500)
    (let [view (r/atom "overview")]
      (ui/run (fn [] (gv/app @board/board view)) :title "faraday console"))))
