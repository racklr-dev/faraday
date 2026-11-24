;; Ring handler: the full page on GET /, the live fragment on the SSE request
;; wrap-datastar opens, the vendored Datastar client, and the action endpoints
;; (dry-run, cancel, run, thread) that return fragments/204 for @get actions.
(ns faraday.console.handler
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [jolt.datastar.core :as ds]
            [jolt.datastar.edn :as de]
            [jolt.datastar.html :as h]
            [faraday.console.board :as board]
            [faraday.console.runtime :as rt]
            [faraday.console.views :as views]
            [faraday.console.views.glimmer :as views.glimmer]))

(defonce runtime (atom nil))
(defonce datastar-js (delay (slurp (io/resource "datastar.js"))))

(defn- query-params [req]
  (let [qs (or (:query-string req) "")]
    (if (str/blank? qs)
      {}
      (into {}
        (for [kv (str/split qs #"&")]
          (let [[k v] (str/split kv #"=" 2)]
            [(keyword k)
             (try (java.net.URLDecoder/decode (or v "") "UTF-8")
                  (catch Exception _ (or v "")))]))))))

(def css
  "body{font-family:ui-monospace,SFMono-Regular,monospace;margin:0;background:#0d1117;color:#c9d1d9;font-size:14px}
#topbar{display:flex;gap:16px;align-items:center;padding:8px 16px;background:#161b22;border-bottom:1px solid #30363d;font-size:13px}
.brand{font-weight:700;color:#58a6ff}
#nav{display:flex;gap:4px;padding:8px 16px;background:#161b22;border-bottom:1px solid #30363d;flex-wrap:wrap}
#nav button{background:#21262d;color:#c9d1d9;border:1px solid #30363d;padding:6px 12px;border-radius:6px;cursor:pointer}
#nav button:hover{background:#30363d}
.view{padding:16px}
h2{margin-top:0}
.card{background:#161b22;border:1px solid #30363d;border-radius:8px;padding:12px;margin-bottom:12px}
.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(280px,1fr));gap:12px}
.gauge{display:flex;align-items:center;gap:8px;margin:6px 0}
.gauge-label{width:40px;font-size:12px}
.gauge-bar{flex:1;height:10px;background:#21262d;border-radius:5px;overflow:hidden}
.gauge-fill{height:100%;background:#58a6ff}
.gauge-num{width:110px;text-align:right;font-size:12px}
table.data{border-collapse:collapse;width:100%;font-size:12px}
table.data th,table.data td{border:1px solid #30363d;padding:4px 8px;text-align:left;max-width:420px;overflow:hidden}
table.data th{background:#21262d}
code,pre.cedar{font-size:12px;color:#79c0ff}
pre.cedar{background:#0d1117;border:1px solid #30363d;border-radius:6px;padding:8px;overflow-x:auto}
.notice{background:#5c1a1a;color:#ff7b72;padding:2px 8px;border-radius:4px}
.d.allow,.d.ok{color:#3fb950}
.d.deny,.d.denied,.d.error{color:#f85149}
.d.running{color:#d29922}
.decision{font-size:18px;font-weight:700}
.decision.allow{color:#3fb950}
.decision.deny,.decision.error{color:#f85149}
.row{display:flex;gap:8px;flex-wrap:wrap}
.row button,.card button{background:#21262d;color:#c9d1d9;border:1px solid #30363d;padding:4px 10px;border-radius:6px;cursor:pointer}
.row button:hover,.card button:hover{background:#30363d}
footer{padding:8px 16px;font-size:11px;color:#8b949e}")

(defn- page [b]
  (h/html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "jolt dashboard"]
     [:style css]
     [:script {:src "/assets/datastar.js" :type "module"}]]
    [:body (ds/init-opts {:signals {:view "overview"}})
     (views/live-region b)]]))

(defn wants-edn?
  "True when this request wants the elements as EDN hiccup (a native glimmer
  client) rather than HTML (the browser): Accept: application/edn, or
  ?namespace=edn."
  [req]
  (or (str/includes? (str (get-in req [:headers "accept"])) "application/edn")
      (= "edn" (get (query-params req) :namespace))))

(defn handler [req]
  (if (:jolt.datastar/sse-request req)
    {:status 200 :body (if (wants-edn? req)
                         (de/hiccup->edn (views.glimmer/live-region @board/board))
                         (h/html (views/live-region @board/board)))}
    (case (:uri req)
      "/"
      {:status 200
       :headers {"Content-Type" (if (wants-edn? req) "application/edn; charset=utf-8" "text/html; charset=utf-8")}
       :body (if (wants-edn? req)
               (de/hiccup->edn (views.glimmer/live-region @board/board))
               (page @board/board))}

      "/assets/datastar.js"
      {:status 200
       :headers {"Content-Type" "text/javascript; charset=utf-8"
                 "Cache-Control" "public, max-age=86400"}
       :body @datastar-js}

      "/dry-run"
      (let [q (query-params req)
            d (rt/dry-run @runtime
                          (or (:principal q) "alice")
                          "RunSandbox"
                          (:image @runtime)
                          (or (:egress q) "egr_clean"))]
        (board/show-dry-result! d)
        {:status 204})

      "/cancel"
      (do (when-let [cid (:call-id (query-params req))]
            (try (rt/cancel! @runtime cid) (catch Exception _ nil)))
          {:status 204})

      "/run"
      (let [q (query-params req)
            agent (or (:agent q) "alice")
            argv (str/split (or (:argv q) "echo hi") #"\s+")
            call {:v 1
                  :call-id (str "call_" (System/nanoTime))
                  :thread-id (str "agent-" agent)
                  :name "run_shell"
                  :display-as (str/join " " argv)
                  :owner agent
                  :args {:argv argv :cpu 1 :mem-mb 512
                         :egress {:ref (or (:egress q) "egr_clean")}}}]
        (try (rt/submit-run! @runtime call) (catch Exception _ nil))
        {:status 204})

      "/thread"
      (let [q (query-params req)
            tid (:thread-id q)
            msgs (if tid (rt/history @runtime tid) [])]
        (board/show-thread! (or tid "") msgs)
        {:status 204})

      {:status 404
       :headers {"Content-Type" "text/plain; charset=utf-8"}
       :body "not found"})))
