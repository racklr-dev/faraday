;; jolt-fanout unit test: emit Thread Messages into a real jolt-ledger.
;; Run: cd jolt-fanout && jolt run test/jolt/fanout_test.clj
(ns jolt.test.fanout
  (:require [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn err-of [thunk]
  (try (thunk) ::no-throw
       (catch Exception e e)))

(defn err-type [thunk]
  (let [e (err-of thunk)]
    (if (= ::no-throw e) ::no-throw (get (ex-data e) :type))))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir") "/jolt-fanout-test-" (System/nanoTime)))

(defn new-test-ledger []
  (ledger/new-ledger {:path (str (tmp-dir) "/ledger.db")}))

(def sample-spec {:v 1 :request-id "r1" :image "img" :argv ["/bin/echo" "ok"]})

;; -- new-runtime -------------------------------------------------------------
(check "new-runtime requires :ledger"
        true (instance? Exception (err-of #(fanout/new-runtime {}))))

(let [l (new-test-ledger)
      m {:fake-memo true}
      rt (fanout/new-runtime {:ledger l :memo m :policy-hook identity})]
  (check "new-runtime holds the ledger" l (:ledger rt))
  (check "new-runtime holds the memo" m (:memo rt))
  (check "new-runtime holds the policy hook" identity (:policy-hook rt))
  (check "close! returns nil" nil (fanout/close! rt))
  (ledger/close! l))

;; -- spawn -------------------------------------------------------------------
(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l})
      msg (fanout/spawn rt nil sample-spec nil)]
  (check "spawn mints a thr_ thread-id" true (boolean (re-find #"^thr_" (:thread-id msg))))
  (check "spawn parent-id is nil at root" nil (:parent-id msg))
  (check "spawn kind" "spawn" (:kind msg))
  (check "spawn seq 0" 0 (:seq msg))
  (check "spawn payload spec" sample-spec (get-in msg [:payload :spec]))
  (check "spawn omits nil memo-key" false (contains? (:payload msg) :memo-key))
  (check "spawn message lands in ledger"
          1 (count (ledger/history l (:thread-id msg))))
  (ledger/close! l))

(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l})
      msg (fanout/spawn rt "thr_parent" sample-spec "memo-1")]
  (check "spawn parent-id links the tree" "thr_parent" (:parent-id msg))
  (check "spawn payload carries memo-key" "memo-1" (get-in msg [:payload :memo-key]))
  (ledger/close! l))

(check "spawn requires a spec"
        true (instance? Exception
                       (err-of #(let [l (new-test-ledger)]
                                  (fanout/spawn (fanout/new-runtime {:ledger l}) nil nil nil)))))

;; -- report / yield on one thread --------------------------------------------
(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l})
      spawn-msg (fanout/spawn rt nil sample-spec nil)
      tid (:thread-id spawn-msg)
      rep (fanout/report rt tid "ok" {:exit-code 0} nil)
      rep2 (fanout/report rt tid "ok" nil nil)]
  (check "report kind" "report" (:kind rep))
  (check "report seq 1" 1 (:seq rep))
  (check "report payload status" "ok" (get-in rep [:payload :status]))
  (check "report payload result" {:exit-code 0} (get-in rep [:payload :result]))
  (check "report omits nil segments" false (contains? (:payload rep) :segments))
  (check "second report omits nil result" false (contains? (:payload rep2) :result))
  (check "report seq 2" 2 (:seq rep2))
  (let [y (fanout/yield rt tid "deadbeef" "snap-1")]
    (check "yield seq 3" 3 (:seq y))
    (check "yield payload" {:state-hash "deadbeef" :snapshot-ref "snap-1"} (:payload y))
    (check "thread history is in seq order" [0 1 2 3]
           (mapv :seq (ledger/history l tid))))
  (check "report rejects bad status"
          true (instance? Exception (err-of #(fanout/report rt tid "nope" nil nil))))
  (ledger/close! l))

;; -- inherit -----------------------------------------------------------------
(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l})
      parent (fanout/spawn rt nil sample-spec nil)
      inh (fanout/inherit rt (:thread-id parent) "snap-9")]
  (check "inherit mints a new thread" true (not= (:thread-id inh) (:thread-id parent)))
  (check "inherit parent-id is the from-thread" (:thread-id parent) (:parent-id inh))
  (check "inherit payload" {:from-thread-id (:thread-id parent) :snapshot-ref "snap-9"}
         (:payload inh))
  (check "inherit is seq 0 of its own thread" 0 (:seq inh))
  (check "inherit thread has one message"
          1 (count (ledger/history l (:thread-id inh))))
  (check "inherit requires snapshot-ref"
          true (instance? Exception (err-of #(fanout/inherit rt "thr_x" ""))))
  (ledger/close! l))

;; -- emit + policy hook ------------------------------------------------------
(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l :policy-hook (fn [msg] (= "spawn" (:kind msg)))})]
  (check "policy hook denies non-spawn"
          :policy-vetoed
          (err-type #(fanout/emit rt {:v 1 :thread-id "t1" :parent-id nil :msg-id "m2"
                                      :kind "system" :ts "x" :payload {}})))
  (check "vetoed message never reached the ledger"
          false (ledger/processed? l "m2"))
  (let [m (fanout/emit rt {:v 1 :thread-id "t1" :parent-id nil :msg-id "m1"
                           :kind "spawn" :ts "x" :payload {:spec sample-spec}})]
    (check "policy hook permits matching kind" "spawn" (:kind m))
    (check "emit returns the appended message with seq" 0 (:seq m))
    (check "permitted msg-id present" true (ledger/processed? l "m1"))))

(let [l (new-test-ledger)
      rt (fanout/new-runtime {:ledger l})
      m (fanout/emit rt {:v 1 :thread-id "t2" :parent-id nil :msg-id "m-user"
                         :kind "user-input" :ts "x" :payload {:text "hi"}})]
  (check "emit appends arbitrary valid kinds" "user-input" (:kind m))
  (check "close! does not close the ledger"
          0 (do (fanout/close! rt)
                (:seq (ledger/append! l {:v 1 :thread-id "t3" :parent-id nil
                                         :msg-id "m3" :kind "system" :ts "x" :payload {}}))))
  (ledger/close! l))

(println "ALL FANOUT TESTS PASSED")
