;; jolt-vm-pool integration test (board nc-56AD): warm reuse + snapshot/restore
;; over a REAL KVM microVM (alpine:3.20 via jolt-microvm/jolt-oci).
;; Requires /dev/kvm access (jm in the kvm group). NEVER `sg kvm`: it makes
;; kvm the primary gid and libkrun virtio-fs guest writes fail EPERM. Run:
;;   cd jolt-vm-pool && sudo -u jm --preserve-env env JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt PATH=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux:$PATH /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/vm_pool_integration.clj
(ns jolt.integration.vm-pool
  (:require [jolt.vm-pool :as pool]
            [jolt.microvm :as microvm]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(def image "alpine:3.20")
(def cache (str (System/getProperty "user.home") "/.cache/jolt-microvm"))

(defn req [id]
  {:v 1 :request-id id :argv ["/bin/echo" "integration"]})

(let [p (pool/new-pool {:image image :cache-dir cache :max-warm 2})]
  (try
    ;; -- phase 1: warm reuse over a real KVM boot ---------------------------
    (let [h (pool/acquire p (req "req-1"))]
      (check "acquire boots a live VM" true (microvm/alive? (:vm h)))
      (let [r (microvm/exec (:vm h) "echo warm-reuse-marker")]
        (check "exec runs on the live VM"
               true (str/includes? (:stdout r) "warm-reuse-marker")))
      (check "release returns warm count 1" 1 (pool/release p h))
      (let [h2 (pool/acquire p (req "req-2"))]
        (check "re-acquire reuses the same warm handle" (:id h) (:id h2))
        (check "reused VM is still alive" true (microvm/alive? (:vm h2)))

        ;; -- phase 2: hibernate to snapshot, restore, marker survives -------
        (check "write the snapshot marker" true
               (str/includes? (:stdout (microvm/exec (:vm h2) "echo snapshot-survivor > /tmp/marker && cat /tmp/marker"))
                              "snapshot-survivor"))
        (let [snap (pool/hibernate p h2)]
          (check "hibernate returns a snap- ref" true (boolean (re-find #"^snap-" snap)))
          (let [h3 (pool/restore p snap)]
            (check "restore returns a live VM" true (microvm/alive? (:vm h3)))
            (check "snapshot marker survives restore" true
                   (str/includes? (:stdout (microvm/exec (:vm h3) "cat /tmp/marker"))
                                  "snapshot-survivor"))
            (pool/release p h3)))))
    ;; -- phase 3: close! shuts everything down ------------------------------
    (check "close! returns :closed" :closed (pool/close! p))
    (check "stats zeroed after close!" {:warm 0 :total 0 :snapshots 0} (pool/stats p))
    (catch Exception e
      (try (pool/close! p) (catch Exception _ nil))
      (throw e))))

(println "VM-POOL INTEGRATION PASSED")
