;; faraday.agent demo: drive a scripted agent through three sandbox execs and
;; print the transcript as it happens. No KVM: the vm-pool/microvm are faked,
;; the ledger/fanout are real, exactly like the unit test.
;; Run: cd agent && jolt run demo/drive.clj
(ns faraday.agent.demo
  (:require [faraday.agent :as agent]
            [faraday.rap :as rap]
            [faraday.ledger :as ldg]
            [faraday.fanout :as fanout]
            [faraday.pool :as pool]
            [jolt.microvm :as microvm]))

;; -- fakes -------------------------------------------------------------------
(defn fake-acquire [p request]
  {:id "sandbox-1" :image (or (:image request) "img") :vm {:fake? true} :boot-opts {}})

(defn fake-release [p handle] 0)

(defn fake-exec [vm command & {:keys [cwd timeout-ms]}]
  {:stdout (str "sandbox says: " command "\n") :stderr "" :exit 0})

(defn -main [& _]
  (let [l (ldg/new-ledger {:path (str (System/getProperty "java.io.tmpdir") "/agent-demo-" (System/nanoTime) "/ledger.db")})
        rt (fanout/new-runtime {:ledger l})
        srv (rap/new-server {:vm-pool {:fake-pool true} :fanout rt})
        a (agent/scripted-agent
           [{:tool-call {:name "run_shell" :args {:argv ["id"]} :display-as "id"}}
            {:tool-call {:name "run_shell" :args {:argv ["ls" "-la" "/"]} :display-as "ls -la /"}}
            {:tool-call {:name "run_shell" :args {:argv ["cat" "/etc/hostname"]} :display-as "cat /etc/hostname"}}
            {:done "sandbox probing complete"}])]
    (with-redefs [faraday.pool/acquire fake-acquire
                  faraday.pool/release fake-release
                  jolt.microvm/exec fake-exec]
      (println "== driving agent: task = \"probe the sandbox\" ==")
      (let [out (agent/run!
                 {:rap srv :agent a :task "probe the sandbox" :thread-id "thr_demo"
                  :on-step (fn [e]
                             (case (:role e)
                               :tool-call (println (str "\n[agent] run: " (or (:display-as e) (:name e))))
                               :tool-result (println (str "[tool]  " (:status e)
                                                          (when-let [seg (:segments e)]
                                                            (str "  -> " (pr-str (map :content seg))))))
                               nil))})]
        (println "\n== outcome ==")
        (println (str "status:     " (:status out)))
        (println (str "result:     " (pr-str (:result out))))
        (println (str "turns:      " (:turns out)))
        (println (str "transcript: " (count (:transcript out)) " entries"))
        (println "\n== ledger thread history (thr_demo) ==")
        (doseq [m (ldg/history l "thr_demo")]
          (println (str "  " (:kind m) " seq=" (:seq m) " ts=" (:ts m))))))))

(apply -main *command-line-args*)
