;; faraday-memo test: real behavior of the content-addressed cache.
;; Run: cd faraday-memo && jolt run test/jolt/memo_test.clj
(ns jolt.test.memo
  (:require [faraday.memo :as memo]
            [faraday.memo.sha256 :as sha]
            [clojure.java.io :as io]))

(def failures (atom 0))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (swap! failures inc))))

(defn check-true [label x] (check label true (boolean x)))
(defn check-false [label x] (check label false (boolean x)))

(let [dir (str "/tmp/faraday-memo-test-" (System/nanoTime))
      m (memo/new-memo {:path dir})]

  ;; the vendored SHA-256 must match the FIPS 180-4 vectors
  (check "sha256 KAT (abc)"
         "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (sha/hex-digest (.getBytes "abc" "UTF-8")))
  (check "sha256 KAT (empty)"
         "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
         (sha/hex-digest (byte-array 0)))
  (check "sha256 KAT (two blocks)"
         "11ee391211c6256460b6ed375957fadd8061cafbb31daf967db875aebd5aaad4"
         (sha/hex-digest (.getBytes (apply str (repeat 60 "a")) "UTF-8")))
  ;; key-of returns an opaque 64-hex key
  (check-true "key-of is 64 hex chars"
              (boolean (re-matches #"[0-9a-f]{64}" (memo/key-of m "abc"))))

  ;; key-of is pure and deterministic
  (check "key-of deterministic"
         (memo/key-of m {:a 1})
         (memo/key-of m {:a 1}))

  ;; = maps with different key order hash identically
  (check "map key order ignored"
         (memo/key-of m {:a 1 :b 2})
         (memo/key-of m {:b 2 :a 1}))

  ;; = sets likewise
  (check "set element order ignored"
         (memo/key-of m #{1 2 3})
         (memo/key-of m #{3 1 2}))

  ;; vector order is significant
  (check-false "vector order matters"
               (= (memo/key-of m [1 2]) (memo/key-of m [2 1])))

  ;; numeric type folding
  (check-false "1 vs 1.0 differ" (= (memo/key-of m 1) (memo/key-of m 1.0)))
  (check "1 vs 1N identical" (memo/key-of m 1) (memo/key-of m 1N))

  ;; put!/get/contains? round-trip, idempotency, stats
  (let [k (memo/put! m {:status "ok" :result 42})]
    (check-true "put! stores" (memo/contains? m k))
    (check "get round-trips" {:status "ok" :result 42} (memo/get m k))
    (check "put! idempotent key" k (memo/put! m {:status "ok" :result 42}))
    (check "stats entries after idempotent put" 1 (:entries (memo/stats m))))

  ;; nested value round-trip
  (let [v {:nested {:list [1 2 3] :set #{4 5} :s "x" :n nil :b true :f 1.5}}
        k (memo/put! m v)]
    (check "nested round-trip" v (memo/get m k)))

  ;; string with a quote and a newline (built from chars, no source escapes)
  (let [tricky (str "a" (char 34) "b" (char 10) "c")]
    (check "string specials round-trip" tricky (memo/get m (memo/put! m tricky))))

  ;; stored nil: get returns nil, contains? disambiguates
  (let [k (memo/put! m nil)]
    (check "stored nil get" nil (memo/get m k))
    (check-true "stored nil contains?" (memo/contains? m k)))

  ;; absent key
  (check "absent get" nil (memo/get m "nope"))
  (check-false "absent contains?" (memo/contains? m "nope"))

  ;; delete!
  (let [k (memo/put! m {:gone true})]
    (check-true "delete present" (memo/delete! m k))
    (check-false "deleted absent" (memo/contains? m k))
    (check-false "delete absent returns false" (memo/delete! m k)))

  ;; stats: entries and bytes both positive after puts
  (let [s (memo/stats m)]
    (check-true "stats entries positive" (pos? (:entries s)))
    (check-true "stats bytes positive" (pos? (:bytes s))))

  ;; close! is a no-op kept for API symmetry
  (check "close! returns nil" nil (memo/close! m))

  ;; cleanup
  (doseq [f (.listFiles (io/file dir))] (.delete f))
  (.delete (io/file dir))

  (if (zero? @failures)
    (println "ALL TESTS PASSED")
    (do (println (str @failures " FAILURES"))
        (System/exit 1))))
