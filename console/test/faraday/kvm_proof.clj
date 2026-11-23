;; Real-KVM proof for the pinned stack (jolt-microvm 80a384134 via vm-pool).
;; Boots alpine:3.20, execs in-guest over SSH, prints real guest output.
;; Run: sudo -u jm --preserve-env env HOME=/home/jm JOLT_BIN=... JOLT_LIBKRUN_DIR=... jolt run test/jolt/kvm_proof.clj
(ns jolt.kvm-proof
  (:require [jolt.microvm :as microvm]))

(def image "alpine:3.20")
(def cache "/home/jm/.cache/jolt-microvm")

(let [vm (microvm/start! {:image image :cache-dir cache
                          :project-dir "/home/jm/projects/jolt/faraday-console"})]
  (try
    (println "BOOTED" (pr-str (microvm/info vm)))
    (println "ALIVE?" (microvm/alive? vm))
    (let [r (microvm/exec vm "uname -a")]
      (println "EXEC-UNAME" (pr-str r)))
    (let [r (microvm/exec vm "echo hello-from-real-guest")]
      (println "EXEC-ECHO" (pr-str r)))
    (let [r (microvm/exec vm "sh -c 'exit 7'")]
      (println "EXEC-EXIT7" (pr-str r)))
    (finally
      (microvm/stop! vm))))
(println "PROOF-DONE")
