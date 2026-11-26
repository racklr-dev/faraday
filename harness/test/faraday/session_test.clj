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
(check "short-thread-id keeps the trailing 8 chars"
       "567890ab"
       (s/short-thread-id "chat-5412f8b3-9c6d-4e0a-b7f1-1234567890ab"))
(check "short-thread-id of a short id is itself"
       "567890ab"
       (s/short-thread-id "567890ab"))

;; -- resolve a full or short thread id ---------------------------------------
(let [tid-a "chat-aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
      tid-b "chat-bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
      amb-1 "chat-cccccccc-cccc-cccc-cccc-1234abcd"
      amb-2 "chat-dddddddd-dddd-dddd-dddd-1234abcd"]
  (s/persist! stores tid-a msgs)
  (s/persist! stores tid-b msgs)
  (s/persist! stores amb-1 msgs)
  (s/persist! stores amb-2 msgs)
  (check "resolve-thread full id is itself" tid-a (s/resolve-thread stores tid-a))
  (check "resolve-thread trailing short form" tid-a (s/resolve-thread stores "aaaaaaaa"))
  (check "resolve-thread accepts the footer's leading #" tid-a (s/resolve-thread stores "#aaaaaaaa"))
  (check "resolve-thread empty id is nil" nil (s/resolve-thread stores ""))
  (check "resolve-thread no match is nil" nil (s/resolve-thread stores "zzzzzzzz"))
  (let [thrown (try (s/resolve-thread stores "abcd")
                    (catch clojure.lang.ExceptionInfo e e))]
    (check-true "resolve-thread ambiguous id throws" (instance? clojure.lang.ExceptionInfo thrown))
    (check "ambiguous message names the matches"
           #{amb-1 amb-2}
           (set (:matches (ex-data thrown))))))

(let [learnings (s/close! stores tid)]
  (check-true "extract records message-count" (contains? learnings :message-count))
  (check "extract message-count is 4" 4 (:message-count learnings)))

(println "ALL SESSION TESTS PASSED")
