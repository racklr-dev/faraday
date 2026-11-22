;; jolt-vm-pool unit test: pool lifecycle + LRU + hibernate/restore, using
;; in-memory fakes for jolt.microvm so it runs with no KVM/libkrun.
;; Run: cd jolt-vm-pool && jolt run test/jolt/vm_pool_test.clj
(ns jolt.test.vm-pool
  (:require [jolt.vm-pool :as pool]
            [jolt.microvm :as microvm]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn err-of [thunk]
  (try (thunk) ::no-throw
       (catch Exception e e)))

(defn err-type [thunk]
  (let [e (err-of thunk)]
    (if (= ::no-throw e) ::no-throw (get (ex-data e) :type))))

;; -- fake jolt.microvm -------------------------------------------------------
(def boots (atom []))
(def stops (atom []))
(def saves (atom []))
(def restores (atom []))
(def vm-seq (atom 0))

(defn fake-start! [opts]
  (swap! boots conj opts)
  {:fake? true :vm-id (swap! vm-seq inc) :boot-opts opts})

(defn fake-stop! [vm]
  (swap! stops conj (:vm-id vm))
  :stopped)

(defn fake-save-snapshot! [vm name]
  (swap! saves conj name)
  :saved)

(defn fake-restore-snapshot! [vm name]
  (swap! restores conj name)
  :restored)

(defn reset-fakes! []
  (reset! boots [])
  (reset! stops [])
  (reset! saves [])
  (reset! restores [])
  (reset! vm-seq 0))

(defmacro with-fake-microvm [& body]
  `(with-redefs [jolt.microvm/start! fake-start!
                 jolt.microvm/stop! fake-stop!
                 jolt.microvm/save-snapshot! fake-save-snapshot!
                 jolt.microvm/restore-snapshot! fake-restore-snapshot!]
     ~@body))

(defn new-test-pool [& [overrides]]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/jolt-vm-pool-test-" (System/nanoTime))]
    (pool/new-pool (merge {:image "img-a" :cache-dir dir} overrides))))

(defn req [& [overrides]]
  (merge {:v 1 :request-id "r1" :image "img-a" :argv ["/bin/echo" "ok"]} overrides))

;; -- validation --------------------------------------------------------------
(check "new-pool requires :image"
        true (instance? Exception (err-of #(pool/new-pool {:cache-dir "/tmp/x"}))))
(check "new-pool requires :cache-dir"
        true (instance? Exception (err-of #(pool/new-pool {:image "i"}))))
(check "new-pool rejects negative :max-warm"
        true (instance? Exception (err-of #(new-test-pool {:max-warm -1}))))
(check "new-pool defaults :max-warm to 4"
        4 (:max-warm (:opts (new-test-pool))))

;; -- acquire/release/LRU -----------------------------------------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool)
        h (pool/acquire p (req))]
    (check "acquire returns a handle with a live vm" true (some? (:vm h)))
    (check "acquire boots when none warm" 1 (count @boots))
    (check "stats: total 1, warm 0" {:warm 0 :total 1 :snapshots 0} (pool/stats p))
    (check "release returns warm count 1" 1 (pool/release p h))
    (check "stats: warm 1 after release" {:warm 1 :total 1 :snapshots 0} (pool/stats p))
    (let [h2 (pool/acquire p (req))]
      (check "re-acquire reuses the warm VM (no new boot)" 1 (count @boots))
      (check "re-acquire returns the same VM" (:vm-id (:vm h)) (:vm-id (:vm h2)))
      (pool/release p h2))))

(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool {:max-warm 2})
        h1 (pool/acquire p (req))
        h2 (pool/acquire p (req))]
    (check "2 boots for 2 acquires on an empty pool" 2 (count @boots))
    (check "stats: total bounded at max-warm" {:warm 0 :total 2 :snapshots 0} (pool/stats p))
    (pool/release p h1)
    (let [h3 (pool/acquire p (req))]
      (check "acquire after release reuses the warm VM (no new boot)" 2 (count @boots))
      (check "reuses the released VM" (:vm-id (:vm h1)) (:vm-id (:vm h3)))
      (pool/release p h2)
      (pool/release p h3)
      (check "stats: both VMs warm" {:warm 2 :total 2 :snapshots 0} (pool/stats p))
      (let [h4 (pool/acquire p (req))]
        (check "reuse is MRU: newest released VM, not the older one"
               (:vm-id (:vm h3)) (:vm-id (:vm h4)))
        (check "no new boot on MRU reuse" 2 (count @boots))
        (pool/release p h4)))))

;; -- admission hook ----------------------------------------------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool {:admission-hook (fn [_] false)})]
    (check "acquire throws :admission-denied when hook denies"
            :admission-denied (err-type #(pool/acquire p (req))))
    (check "no boot when denied" 0 (count @boots))))

(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool {:admission-hook (fn [_] true)})]
    (pool/acquire p (req))
    (check "admission hook truthy admits" 1 (count @boots))))

;; -- image keying + request->boot-opts ---------------------------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool)
        hb (pool/acquire p (req {:image "img-b" :cpu 2 :mem-mb 256
                                 :mounts [{:host-path "/h" :tag "data"}]}))]
    (let [bo (first @boots)]
      (check "request image overrides pool image" "img-b" (:image bo))
      (check "cpu maps to :cpus" 2 (:cpus bo))
      (check "mem-mb maps to :memory-mib" 256 (:memory-mib bo))
      (check "mount maps host-path/tag to microvm mount"
             {:host "/h" :guest "/mnt/data" :tag "data"}
             (first (:mounts bo))))
    (pool/release p hb)
    (let [ha (pool/acquire p (req))]
      (check "acquire of a different image boots fresh" 2 (count @boots))
      (pool/release p ha))
    (let [hb2 (pool/acquire p (req {:image "img-b"}))]
      (check "reuse is keyed by image (img-b reuses, no boot)" 2 (count @boots))
      (check "reuses the img-b VM" (:vm-id (:vm hb)) (:vm-id (:vm hb2)))
      (pool/release p hb2))))

;; -- concurrent acquire blocks at :max-warm until a release ------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool {:max-warm 1})
        h1 (pool/acquire p (req))
        blocked (future (pool/acquire p (req)))]
    (Thread/sleep 200)
    (check "second acquire blocks while the only VM is busy" true (not (future-done? blocked)))
    (check "no boot while blocked" 1 (count @boots))
    (pool/release p h1)
    (let [h2 (deref blocked 5000 :timeout)]
      (check "blocked acquire unblocks after release" true (not= :timeout h2))
      (check "unblocked acquire reused the warm VM (no new boot)" 1 (count @boots))
      (check "reused the released VM" (:vm-id (:vm h1)) (:vm-id (:vm h2)))
      (pool/release p h2))))

;; -- hibernate / restore -----------------------------------------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool)
        h (pool/acquire p (req))
        snap (pool/hibernate p h)]
    (check "hibernate returns a snap- ref" true (boolean (re-find #"^snap-" snap)))
    (check "hibernate saved the snapshot" [snap] @saves)
    (check "hibernate stopped the VM" [(:vm-id (:vm h))] @stops)
    (check "stats after hibernate" {:warm 0 :total 0 :snapshots 1} (pool/stats p))
    (check "handle no longer live after hibernate"
            true (instance? Exception (err-of #(pool/release p h))))
    (let [h2 (pool/restore p snap)]
      (check "restore boots a fresh VM from the snapshot" 2 (count @boots))
      (check "restore restored the snapshot" [snap] @restores)
      (check "restore returns a live handle" true (some? (:vm h2)))
      (check "restore lands warm, not busy" {:warm 1 :total 1 :snapshots 1} (pool/stats p))
      (pool/release p h2))))

;; -- error paths + close! ----------------------------------------------------
(with-fake-microvm
  (reset-fakes!)
  (let [p (new-test-pool)
        h (pool/acquire p (req))]
    (check "release of an unknown handle throws"
            true (instance? Exception (err-of #(pool/release p {:id "nope" :image "x" :vm nil :boot-opts nil}))))
    (check "restore of an unknown snapshot-ref throws"
            true (instance? Exception (err-of #(pool/restore p "snap-999"))))
    (pool/release p h)
    (let [snap-dir (str (:cache-dir (:opts p)) "/snapshots")]
      (.mkdirs (java.io.File. snap-dir))
      (check "close! returns :closed" :closed (pool/close! p))
      (check "close! stops every live VM" [(:vm-id (:vm h))] @stops)
      (check "close! removes the snapshots dir"
              false (.exists (java.io.File. snap-dir)))
      (check "stats zeroed after close!" {:warm 0 :total 0 :snapshots 0} (pool/stats p)))))

(println "ALL VM-POOL UNIT TESTS PASSED")
