;; one-off diagnostic: bake exec-daemon into a distroless session, boot it
;; mode-exec (no net), capture the guest's stdout/stderr. Run via jolt run.
(ns jolt.diag-daemon-boot
  (:require [jolt.microvm.rootfs :as rootfs]
            [jolt.exec.bundle :as bundle]
            [jolt.microvm.deps :as guest-deps]
            [clojure.data.json :as json])
  (:import [java.io File]))

(let [image "gcr.io/distroless/python3-debian13:latest"
      cache "/tmp/jolt-exec-cache"
      session (rootfs/prepare! image cache)
      guest-exe (bundle/bundle-daemon! "/tmp/exec-daemon" session
                  :shared-libs (:shared-libs (guest-deps/guest-deps (System/getProperty "user.dir"))))
      cfg {"mode" "exec"
           "rootfs_path" session
           "cpus" 1
           "memory_mib" 256
           "exec" [guest-exe "8080"]}
      jb (or (System/getenv "JOLT_BIN") "jolt")
      out (File/createTempFile "diag-out" ".log")
      err (File/createTempFile "diag-err" ".log")
      pb (ProcessBuilder. [jb "run" "-m" "jolt.microvm.runner" (json/write-str cfg)])
      _ (.directory pb (File. (System/getProperty "user.dir")))
      env (.environment pb)
      _ (.put env "JOLT_SKIP_MIN_VERSION" "1")
      _ (.put env "LD_LIBRARY_PATH" "/tmp/libkrun/target/release")
      _ (.redirectOutput pb out)
      _ (.redirectError pb err)
      proc (.start pb)
      done? (.waitFor proc 20 java.util.concurrent.TimeUnit/SECONDS)]
  (when-not done? (.destroyForcibly proc))
  (println "session" session)
  (println "guest-exe" guest-exe)
  (println "exit" (if done? (.exitValue proc) :killed))
  (println "STDOUT:" (slurp out))
  (println "STDERR:" (slurp err)))

(System/exit 0)
