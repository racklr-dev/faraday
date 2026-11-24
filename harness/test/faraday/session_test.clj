;; faraday-harness session test: the chat's durable session layer — persist,
;; resume, history, and the post-session extract! orchestrator. Hermetic.
(ns jolt.test.session
  (:require [faraday.harness.session :as s]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(def root (str (System/getProperty "java.io.tmpdir") "/faraday-session-" (System/nanoTime)))
(.mkdirs (java.io.File. root))

(def stores (s/open root))
(def tid "chat-test")

(def msgs [{:role :user :text "hi"}
           {:role :assistant :text "hello"}
           {:role :tool-call :name "board" :args {}}
           {:role :tool-result :name "board" :status :ok :exit 0 :text "open (0)"}])

(check "resume nil before persist" nil (s/resume stores tid))
(s/persist! stores tid msgs)
(check "resume round-trips the transcript" msgs (s/resume stores tid))
(check "history has all entries" 4 (count (s/history stores tid)))
(check "history text survives" "hi" (:text (first (s/history stores tid))))

;; re-persist the same transcript must not duplicate (idempotent by msg-id)
(s/persist! stores tid msgs)
(check "history stays single copy after re-persist" 4 (count (s/history stores tid)))

(check "default-thread-id is fresh per call" false (= (s/default-thread-id) (s/default-thread-id)))

(let [learnings (s/close! stores tid)]
  (check-true "extract records message-count" (contains? learnings :message-count))
  (check "extract message-count is 4" 4 (:message-count learnings)))

(println "ALL SESSION TESTS PASSED")
