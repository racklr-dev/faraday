;; jolt multiplexing probe: N concurrent rap invokes over a SMALL warm pool
;; (max-warm K) against real KVM. Counts real microvm/start! boots to expose
;; whether pool/acquire multiplexes or races under concurrency. Run:
;;   cd jolt-rap && sudo -u jm --preserve-env env JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt PATH=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux:$PATH /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/multiplex_probe.clj
(ns jolt.multiplex-probe
  (:require [jolt.rap :as rap]
            [jolt.vm-pool :as pool]
            [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]
            [jolt.microvm :as microvm]))

(def N 6)
(def K 2)
(def image "alpine:3.20")
(def cache (str (System/getProperty "user.home") "/.cache/jolt-microvm"))
(def pd (System/getProperty "user.dir"))
(def boots (atom 0))
(def real-start! jolt.microvm/start!)

(let [lg (ledger/new-ledger {:path (str (System/getProperty "java.io.tmpdir") "/jolt-mp-" (System/nanoTime))})
      rt (fanout/new-runtime {:ledger lg})
      p (pool/new-pool {:image image :cache-dir cache :project-dir pd :max-warm K})
      srv (rap/new-server {:vm-pool p :fanout rt})]
  (with-redefs [jolt.microvm/start! (fn [opts] (swap! boots inc) (real-start! opts))]
    (let [t0 (System/nanoTime)
          jobs (mapv (fn [i]
                       (let [cid (str "mp-" i) term (promise)]
                         (rap/on-result srv cid #(deliver term %))
                         (rap/invoke srv {:v 1 :thread-id (str "thr-mp-" i) :name "run_shell" :call-id cid
                                          :args {:argv ["/bin/echo" (str "mp-marker-" i)]}})
                         term))
                     (range N))
          frames (mapv #(deref % 600000 :timeout) jobs)
          t1 (System/nanoTime)
          wall-ms (long (/ (- t1 t0) 1000000.0))
          oks (count (filter #(= "ok" (:status %)) frames))]
      (doseq [[i f] (map-indexed vector frames)]
        (when-not (= "ok" (:status f))
          (println "FAILED" i (:status f) (:error f))))
      (println "N" N "K" K "boots" @boots "oks" oks "/" N "wall-ms" wall-ms)
      (println "execs-per-sec" (double (/ N (/ wall-ms 1000.0))))
      (println "pool-stats" (pool/stats p))))
  (rap/close! srv)
  (pool/close! p)
  (ledger/close! lg))

(println "MULTIPLEX PROBE DONE")
