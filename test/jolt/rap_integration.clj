;; jolt-rap integration test (board nc-9464): harness invokes a sandbox tool
;; through RAP against a REAL KVM microVM and receives the async result.
;; Requires /dev/kvm (jm in the kvm group). NEVER `sg kvm`: it makes kvm the
;; primary gid and libkrun virtio-fs guest writes fail EPERM. Run:
;;   cd jolt-rap && sudo -u jm --preserve-env env JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt PATH=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux:$PATH /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/rap_integration.clj
(ns jolt.integration.rap
  (:require [jolt.rap :as rap]
            [jolt.vm-pool :as pool]
            [jolt.fanout :as fanout]
            [jolt.ledger :as ledger]
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
(def project-dir (System/getProperty "user.dir"))

(let [lg (ledger/new-ledger {:path (str (System/getProperty "java.io.tmpdir") "/jolt-rap-int-" (System/nanoTime))})
      rt (fanout/new-runtime {:ledger lg})
      p (pool/new-pool {:image image :cache-dir cache :project-dir project-dir :max-warm 1})
      srv (rap/new-server {:vm-pool p :fanout rt})
      await (fn [p] (deref p 300000 :timeout))]
  (try
    (let [cid "rap-int-1" term (promise)]
      (rap/on-result srv cid #(deliver term %))
      (check "invoke returns supplied call-id" cid
             (rap/invoke srv {:v 1 :thread-id "thr-int" :name "run_shell" :call-id cid
                              :args {:argv ["/bin/echo" "rap-integration-marker"]}}))
      (let [f (await term)]
        (check "async result delivered" true (not= :timeout f))
        (check "status ok" "ok" (:status f))
        (check "exit 0" 0 (:exit-code f))
        (let [out (apply str (map :content (:segments f)))]
          (check "stdout has marker" true (str/includes? out "rap-integration-marker"))))
      (let [report (last (filter #(= "report" (:kind %)) (ledger/history lg "thr-int")))]
        (check "ledger holds a report" true (some? report))
        (check "report status ok" "ok" (get-in report [:payload :status]))))
    (let [cid "rap-int-2" term (promise)]
      (rap/on-result srv cid #(deliver term %))
      (rap/invoke srv {:v 1 :thread-id "thr-int" :name "run_shell" :call-id cid
                       :args {:argv ["/bin/echo" "second-marker"]}})
      (let [f (await term)
            out (apply str (map :content (:segments f)))]
        (check "warm-reuse second result ok" "ok" (:status f))
        (check "second stdout marker" true (str/includes? out "second-marker"))))
    (check "server close!" nil (rap/close! srv))
    (check "pool close!" :closed (pool/close! p))
    (ledger/close! lg)
    (catch Exception e
      (try (rap/close! srv) (catch Exception _ nil))
      (try (pool/close! p) (catch Exception _ nil))
      (try (ledger/close! lg) (catch Exception _ nil))
      (throw e))))

(println "RAP INTEGRATION PASSED")
