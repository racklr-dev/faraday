;; jolt-fanout integration test (board nc-6F47): build a fanout tree in a real
;; jolt-ledger, cross the durable boundary (close + reopen), read every thread
;; back, reconstruct the parent->children edges, and verify the tree + payloads
;; round-tripped.
;; Run: cd jolt-fanout && jolt run test/jolt/fanout_integration_test.clj
(ns jolt.integration.fanout
  (:require [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir")
       "/jolt-fanout-integration-" (System/nanoTime)))

(def specs
  {:root {:v 1 :request-id "req-root" :image "img/root" :argv ["/bin/root"]}
   :c1   {:v 1 :request-id "req-c1"   :image "img/c1"   :argv ["/bin/c1"]}
   :c2   {:v 1 :request-id "req-c2"   :image "img/c2"   :argv ["/bin/c2"]}
   :g1   {:v 1 :request-id "req-g1"   :image "img/g1"   :argv ["/bin/g1"]}})

(defn build! [path]
  "Build the tree, return {:threads {label thread-id} :root-msg <spawn map>}."
  (let [l  (ledger/new-ledger {:path path})
        rt (fanout/new-runtime {:ledger l})]
    (try
      (let [root (fanout/spawn rt nil (:root specs) nil)
            c1   (fanout/spawn rt (:thread-id root) (:c1 specs) "memo-c1")
            c2   (fanout/spawn rt (:thread-id root) (:c2 specs) nil)
            g1   (fanout/spawn rt (:thread-id c1) (:g1 specs) nil)
            i1   (fanout/inherit rt (:thread-id c1) "snap-c1")
            _    (fanout/report rt (:thread-id root) "ok" {:exit-code 0} nil)
            _    (fanout/report rt (:thread-id c1) "error" {:code "E1"} nil)
            _    (fanout/yield rt (:thread-id c2) "hash-c2" "snap-c2")]
        ;; in-build sanity, before the durability boundary
        (check "build: root spawn seq 0" 0 (:seq root))
        (check "build: c1 parent is root" (:thread-id root) (:parent-id c1))
        (check "build: g1 parent is c1" (:thread-id c1) (:parent-id g1))
        (check "build: i1 parent is c1 (inherit)" (:thread-id c1) (:parent-id i1))
        {:threads {:root (:thread-id root)
                   :c1   (:thread-id c1)
                   :c2   (:thread-id c2)
                   :g1   (:thread-id g1)
                   :i1   (:thread-id i1)}
         :root-msg root})
      (finally
        (fanout/close! rt)
        (ledger/close! l)))))

(defn verify! [path built]
  (let [threads (:threads built)
        l (ledger/new-ledger {:path path})]
    (try
      (let [hroot (ledger/history l (:root threads))
            hc1   (ledger/history l (:c1 threads))
            hc2   (ledger/history l (:c2 threads))
            hg1   (ledger/history l (:g1 threads))
            hi1   (ledger/history l (:i1 threads))]
        ;; --- per-thread history round-trip (seq order, kinds, payloads) ---
        (check "root: seq order" [0 1] (mapv :seq hroot))
        (check "root: kinds" ["spawn" "report"] (mapv :kind hroot))
        (check "root: root-of-tree parent-id nil" nil (:parent-id (first hroot)))
        (check "root: spawn spec round-trips"
               (:root specs) (get-in (first hroot) [:payload :spec]))
        (check "root: report status" "ok" (get-in (second hroot) [:payload :status]))
        (check "root: report result" {:exit-code 0} (get-in (second hroot) [:payload :result]))

        (check "c1: seq order" [0 1] (mapv :seq hc1))
        (check "c1: kinds" ["spawn" "report"] (mapv :kind hc1))
        (check "c1: parent is root" (:root threads) (:parent-id (first hc1)))
        (check "c1: spawn memo-key round-trips" "memo-c1" (get-in (first hc1) [:payload :memo-key]))
        (check "c1: report status" "error" (get-in (second hc1) [:payload :status]))

        (check "c2: seq order" [0 1] (mapv :seq hc2))
        (check "c2: kinds" ["spawn" "yield"] (mapv :kind hc2))
        (check "c2: parent is root" (:root threads) (:parent-id (first hc2)))
        (check "c2: yield payload"
               {:state-hash "hash-c2" :snapshot-ref "snap-c2"}
               (:payload (second hc2)))

        (check "g1: single spawn, seq 0" [0] (mapv :seq hg1))
        (check "g1: parent is c1" (:c1 threads) (:parent-id (first hg1)))
        (check "g1: spec round-trips" (:g1 specs) (get-in (first hg1) [:payload :spec]))

        (check "i1: single inherit, seq 0" [0] (mapv :seq hi1))
        (check "i1: parent is c1" (:c1 threads) (:parent-id (first hi1)))
        (check "i1: inherit payload"
               {:from-thread-id (:c1 threads) :snapshot-ref "snap-c1"}
               (:payload (first hi1)))

        ;; --- tree structure: reconstruct parent->children from durable parent-id
        (let [spawns [(first hroot) (first hc1) (first hc2) (first hg1) (first hi1)]
              children (reduce (fn [acc msg]
                                 (if-let [pid (:parent-id msg)]
                                   (update acc pid (fnil conj #{}) (:thread-id msg))
                                   acc))
                               {} spawns)]
          (check "tree: root children are c1 and c2"
                 #{(:c1 threads) (:c2 threads)}
                 (get children (:root threads)))
          (check "tree: c1 children are g1 and i1"
                 #{(:g1 threads) (:i1 threads)}
                 (get children (:c1 threads)))
          (check "tree: c2 has no children" nil (get children (:c2 threads)))
          (check "tree: five threads total" 5 (count spawns)))

        ;; the durable boundary preserved dedup: the root spawn msg-id still dedups
        (check "reopen: root spawn msg-id still dedups"
               {:seq 0 :deduped? true}
               (ledger/append! l (dissoc (:root-msg built) :seq))))
      (finally (ledger/close! l)))))

(let [path (str (tmp-dir) "/ledger.db")]
  (verify! path (build! path)))

(println "ALL FANOUT INTEGRATION TESTS PASSED")
