;; faraday-console handler test: content negotiation between the browser
;; (HTML) and a native glimmer client (EDN hiccup). Hermetic: no KVM, no
;; server, just the handler + the board ratom.
(ns jolt.test.console-handler
  (:require [faraday.console.handler :as handler]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(check-true "wants-edn? via accept header"
            (handler/wants-edn? {:headers {"accept" "application/edn"}}))
(check-true "wants-edn? via query param"
            (handler/wants-edn? {:query-string "namespace=edn"}))
(check "wants-edn? false by default" false
       (handler/wants-edn? {:headers {"accept" "text/html"}}))

(let [r (handler/handler {:jolt.datastar/sse-request true
                          :headers {"accept" "application/edn"}
                          :query-string ""})]
  (check "sse edn status" 200 (:status r))
  (check-true "sse edn body reads as EDN"
              (try (edn/read-string (:body r)) true (catch Exception _ false))))

(let [r (handler/handler {:jolt.datastar/sse-request true
                          :headers {"accept" "text/html"}
                          :query-string ""})]
  (check "sse html status" 200 (:status r))
  (check-true "sse html body has a div" (str/includes? (:body r) "<div")))

(println "ALL CONSOLE HANDLER TESTS PASSED")
