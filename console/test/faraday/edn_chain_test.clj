;; faraday console <-> native client data chain: the handler's EDN body must
;; decode through glimmer-datastar-client's edn codec into glimmer hiccup.
;; Hermetic: no KVM, no server, no network.
(ns jolt.test.console-edn-chain
  (:require [faraday.console.handler :as handler]
            [jolt.datastar.client.codec :as codec]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(def known-widgets #{:vbox :hbox :label :scroll :frame :entry :button :window})

(defn walk-tags [x]
  (cond
    (vector? x)
    (let [head (first x)]
      (if (keyword? head)
        (into [head] (mapcat walk-tags) (rest x))
        (mapcat walk-tags x)))
    (map? x) (mapcat walk-tags (vals x))
    :else nil))

(let [body (:body (handler/handler {:jolt.datastar/sse-request true
                                    :headers {"accept" "application/edn"}
                                    :query-string ""}))
      elems ((:decode-elements codec/edn) body)]
  (check "edn chain decodes to one root element" 1 (count elems))
  (check "edn chain root is a glimmer vbox" :vbox (ffirst elems))
  (check-true "edn chain root carries the live-region children"
              (pos? (count (rest (first elems)))))
  (let [tags (set (walk-tags (first elems)))]
    (check-true "every tag is a known glimmer widget"
                (every? known-widgets tags))))

(println "ALL EDN CHAIN TESTS PASSED")
