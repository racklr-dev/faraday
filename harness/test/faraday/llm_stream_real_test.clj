;; llm_stream_real_test — exercise the REAL faraday.harness.llm/stream-http
;; (curl subprocess) against a local SSE server, so the read loop's reassembly
;; is actually covered. The other llm test stubs stream-http with with-redefs,
;; which is exactly why the missing (recur) bug was invisible.
;;
;; Run: cd harness && jolt run test/faraday/llm_stream_real_test.clj
(ns jolt.test.llm-stream-real
  (:require [faraday.harness.llm :as llm]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(def sse-server-py
  (str/join "\n"
            ["import http.server, time, json, sys"
             "class H(http.server.BaseHTTPRequestHandler):"
             "    def do_POST(self):"
             "        n = int(self.headers.get('Content-Length', 0))"
             "        self.rfile.read(n)"
             "        self.send_response(200)"
             "        self.send_header('Content-Type', 'text/event-stream')"
             "        self.end_headers()"
             "        def emit(p):"
             "            self.wfile.write(('data: ' + json.dumps(p) + '\\n\\n').encode())"
             "            self.wfile.flush()"
             "            time.sleep(0.01)"
             "        emit({'choices':[{'index':0,'delta':{'role':'assistant','content':''},'finish_reason':None}]})"
             "        emit({'choices':[{'index':0,'delta':{'content':'hel'},'finish_reason':None}]})"
             "        emit({'choices':[{'index':0,'delta':{'content':'lo'},'finish_reason':None}]})"
             "        emit({'choices':[{'index':0,'delta':{},'finish_reason':'stop'}]})"
             "        self.wfile.write(b'data: [DONE]\\n\\n')"
             "        self.wfile.flush()"
             "    def log_message(self, *a):"
             "        pass"
             "http.server.HTTPServer(('127.0.0.1', int(sys.argv[1])), H).serve_forever()"]))

(def port 18739)
(def py-file (java.io.File. (str (System/getProperty "java.io.tmpdir") "/faraday-sse-" port ".py")))
(spit py-file sse-server-py)
(def srv (-> (ProcessBuilder. ["python3" (.getPath py-file) (str port)])
             (.redirectErrorStream true)
             (.start)))

;; give the server a moment to bind
(Thread/sleep 500)

(def deltas (atom []))
(def cfg {:base-url (str "http://127.0.0.1:" port)
          :api-key "test"
          :model "test"
          :stream true
          :temperature 0})

(try
  (let [r (llm/completion->result
           cfg
           [{:role "user" :content "hi"}]
           (fn [t] (swap! deltas conj t)))]
    (check "real stream-http reassembles content into the plain-text action"
           "hello" (:action r))
    (check "real stream-http called on-delta per content delta (>= 4)"
           true (>= (count @deltas) 4)))
  (finally
    (.destroyForcibly srv)
    (.delete py-file)))

(println "LLM STREAM REAL TEST PASSED")