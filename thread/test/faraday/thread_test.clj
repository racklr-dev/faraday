;; faraday-thread unit test: thread-keyed sandbox + repo store, using fakes for
;; faraday.pool and jolt.microvm so it runs with no KVM/libkrun.
;; Run: cd faraday-thread && jolt run test/faraday/thread_test.clj
(ns jolt.test.thread
  (:require [faraday.thread :as thread]
            [faraday.pool :as pool]
            [jolt.microvm :as microvm]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(def acquires (atom []))
(def handles (atom {}))
(def hibernates (atom {}))
(def restores (atom []))
(def execs (atom []))
(def next-id (atom 0))

(defn fake-acquire [_ request]
  (let [id (str "h" (swap! next-id inc))]
    (swap! acquires conj request)
    (let [h {:id id :vm {:fake? true :vm id}}]
      (swap! handles assoc id h)
      h)))

(defn fake-release [_ handle] :released)

(defn fake-hibernate [_ handle]
  (let [snap (str "snap_" (:id handle))]
    (swap! hibernates assoc snap handle)
    snap))

(defn fake-restore [_ snap]
  (swap! restores conj snap)
  (get @hibernates snap))

(defn fake-exec [_vm cmd & {:keys [timeout-ms]}]
  (swap! execs conj cmd)
  {:exit 0 :stdout "ok" :stderr ""})

(defn fresh-store []
  (thread/new-store {:pool ::pool
                     :repo-root (str (System/getProperty "java.io.tmpdir")
                                     "/thr-test-" (System/nanoTime))}))

(with-redefs [pool/acquire fake-acquire
              pool/release fake-release
              pool/hibernate fake-hibernate
              pool/restore fake-restore
              microvm/exec fake-exec]
  (let [store (fresh-store)
        req {:v 1 :request-id "r1" :image "img" :argv ["echo" "hi"]
             :repo {:mode "direct" :host-path "/srv/repos/demo"}}]
    (let [r (thread/exec! store "thr-a" req)]
      (check "exec returns exit 0" 0 (:exit r))
      (check "one acquire for first call" 1 (count @acquires))
      (check "repo mount host-path" "/srv/repos/demo"
             (get-in (last @acquires) [:mounts 0 :host-path]))
      (check "repo mount tag" "repo" (get-in (last @acquires) [:mounts 0 :tag]))
      (check "exec ran in repo cwd" true (str/starts-with? (last @execs) "cd /mnt/repo && ")))
    (thread/exec! store "thr-a" (assoc req :request-id "r2"))
    (check "same thread reuses sandbox (still one acquire)" 1 (count @acquires))
    (thread/exec! store "thr-b" (assoc req :request-id "r3" :repo nil))
    (check "different thread acquires again" 2 (count @acquires))
    (check "no repo mount when thread has none" nil
           (get-in (last @acquires) [:mounts 0 :host-path]))
    (let [snap (thread/yield! store "thr-a")]
      (check "yield returns a snap- ref" true (str/starts-with? snap "snap_"))
      (check "store now hibernated" 1 (:hibernated (thread/stats store)))
      (let [h (thread/resume! store "thr-a")]
        (check "resume restored the handle" "h1" (:id h))
        (check "store live again" 2 (:live (thread/stats store)))
        (check "nothing hibernated now" 0 (:hibernated (thread/stats store)))))
    (let [hook (thread/rap-hook store)
          out (hook {:thread-id "thr-a" :v 1 :request-id "r4" :image "img" :argv ["true"]})]
      (check "rap-hook status :ok" :ok (:status out))
      (check "rap-hook stdout" "ok" (:stdout out)))
    (thread/close-thread! store "thr-a")
    (check "close-thread forgets thr-a" nil (get-in @(:state store) [:threads "thr-a"]))
    (check "thr-b still held" 1 (count (get-in @(:state store) [:threads])))))
(println "ALL THREAD TESTS PASSED")
