;; faraday-memo smoke: minimal critical-path gate for CI.
;; Topic (board nc-40AE): cache hit/miss round-trip survives the durable boundary.
;; Fail-fast: the first mismatch prints and exits 1.
;; Run: cd faraday-memo && jolt run test/jolt/memo_smoke.clj
(ns jolt.smoke.memo
  (:require [faraday.memo :as memo]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn tmp-dir []
  (str (System/getProperty "java.io.tmpdir")
       "/faraday-memo-smoke-" (System/nanoTime)))

(let [dir (tmp-dir)]
  ;; -- phase 1: fresh memo, put + hit + miss -------------------------------
  (let [m (memo/new-memo {:path dir})]
    (try
      ;; a value is cached under its content-hash key
      (let [v {:status "ok" :result 42}
            k (memo/put! m v)]
        (check "put! returns a 64-hex key"
               true (boolean (re-matches #"[0-9a-f]{64}" k)))
        (check "hit: get returns the stored value"
               v (memo/get m k))
        (check "hit: contains? sees the key"
               true (memo/contains? m k))
        (check "put! is idempotent: same key on repeat"
               k (memo/put! m v))
        (check "miss: absent key get returns nil"
               nil (memo/get m (str k "deadbeef")))
        (check "miss: absent key contains? is false"
               false (memo/contains? m (str k "deadbeef")))
        (check "stats counts exactly one entry after idempotent put"
               1 (:entries (memo/stats m))))
      ;; a stored nil: get returns nil but contains? disambiguates
      (let [nk (memo/put! m nil)]
        (check "stored nil: get returns nil"
               nil (memo/get m nk))
        (check "stored nil: contains? disambiguates from a miss"
               true (memo/contains? m nk))
        (check "stats counts two entries"
               2 (:entries (memo/stats m))))
      (finally (memo/close! m))))

  ;; -- phase 2: reopen, hit against persisted files -------------------------
  (let [m (memo/new-memo {:path dir})]
    (try
      ;; recompute the key of the phase-1 value; it must still be present
      (let [v {:status "ok" :result 42}
            k (memo/key-of m v)]
        (check "reopen: hit survives the durable boundary"
               v (memo/get m k))
        (check "reopen: contains? sees the persisted key"
               true (memo/contains? m k))
        (check "reopen: stats still counts two entries"
               2 (:entries (memo/stats m)))
        (check "reopen: miss on a fresh key"
               nil (memo/get m (memo/key-of m {:never true}))))
      (finally (memo/close! m)))))

(println "MEMO SMOKE PASSED")
