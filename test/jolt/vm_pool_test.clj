;; jolt-vm-pool stub test: pins the public API surface.
;; Run: cd jolt-vm-pool && jolt run test/jolt/vm_pool_test.clj
(ns jolt.test.vm-pool
  (:require [jolt.vm-pool :as pool]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn stub-err [thunk]
  (try (thunk) ::no-throw
       (catch Exception e (ex-message e))))

(defn stub? [msg]
  (boolean (re-find #"^not implemented" (or msg ""))))

(def sample-req
  {:v 1
   :request-id "req_test"
   :image "oci://ghcr.io/jolt-lang/distroless-base@sha256:abc"
   :argv ["/bin/echo" "ok"]})

(check "new-pool defined" true (fn? pool/new-pool))
(check "new-pool stub" true (stub? (stub-err #(pool/new-pool {:image "x" :cache-dir "/tmp/p"}))))
(check "acquire stub" true (stub? (stub-err #(pool/acquire nil sample-req))))
(check "release stub" true (stub? (stub-err #(pool/release nil :h))))
(check "hibernate stub" true (stub? (stub-err #(pool/hibernate nil :h))))
(check "restore stub" true (stub? (stub-err #(pool/restore nil "snap"))))
(check "stats stub" true (stub? (stub-err #(pool/stats nil))))
(check "close! stub" true (stub? (stub-err #(pool/close! nil))))
