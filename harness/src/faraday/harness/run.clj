;; faraday.harness.run — the honest end-to-end loop runner shared by the CLI
;; (faraday.harness.main) and the chat shell (faraday.harness.chat).
;; One source of truth for: default-task-manifest, the yieldable-scheduler run
;; over host exec (no KVM) or a warm faraday-pool microVM, the model-agent
;; deps resolution, and run-task! (task -> resolve hooks -> compile middleware
;; spine -> launch -> record hook_runs -> finalize the honesty invariant).
;; run-manifest! is the bare loop runner for the shell's /run <manifest.edn>
;; and /spawn <manifest.edn> [N] commands: no taskboard, no hooks, N agents.
(ns faraday.harness.run
  (:require [faraday.harness :as h]
            [faraday.harness.config :as config]
            [faraday.harness.llm :as llm]
            [faraday.agent.scheduler :as sched]
            [faraday.agent.loop :as loop]
            [faraday.agent.mw :as mw]
            [faraday.thread :as thread]
            [faraday.pool :as pool]
            [jolt.microvm :as microvm]
            [faraday.ledger :as ldg]
            [faraday.memo :as memo]
            [faraday.memory :as memory]
            [faraday.fanout :as fanout]
            [faraday.rap :as rap]
            [clojure.java.io :as io]))

(defn- open-db [root]
  (h/new-harness {:path (str root "/.faraday/harness.db")}))

(defn default-task-manifest
  "The scripted fallback loop for a task with no manifest override: one echo
  step, then done. Kept as data so it stores/serializes like any loop manifest."
  [title]
  {:v 1
   :loop-id (str "task-" (h/sha-hex title))
   :name (str "task: " title)
   :agent {:kind "script"
           :script [{:tool-call {:name "run_shell" :args {:argv ["echo" (str "working on: " title)]} :display-as "echo"}}
                    {:done {:result :ok}}]}
   :max-turns 8})

(defn- scheduler-runs!
  "Launch n yieldable loops of manifest over a faraday.thread store backed by
  pool-handle, and wait for all. Returns the scheduler observe maps in launch
  order. step-fns and goal-fns are vectors of length n (script manifests need a
  fresh step-fn per thread; a model step-fn is reusable but the vector keeps one
  shape for both)."
  [root ledg memo memy rt pool-handle manifest task step-fns goal-fns n]
  (let [store (thread/new-store {:pool pool-handle
                                 :repo-root (str root "/.faraday/repos")})
        srv (rap/new-server {:fanout rt :run-hook (thread/rap-hook store)})
        sch (sched/new-scheduler {:srv srv :store store :rt rt :mem memy})]
    (sched/launch! sch {:manifest manifest :task task :n n
                        :step-fns step-fns :goal-fns goal-fns})
    (sched/wait-all sch)))

(defn- scheduler-run!
  "scheduler-runs! for one thread; returns the single observe map."
  [root ledg memo memy rt pool-handle manifest task step-fn goal-fn]
  (first (scheduler-runs! root ledg memo memy rt pool-handle manifest task
                          [step-fn] [goal-fn] 1)))

(defn- host-launch!
  "scheduler-runs! over in-process host exec (no KVM): the with-redefs seam the
  real faraday-pool fills. Exercises the full yield/park/resume/hibernate state
  machine; warm-VM reuse and snapshot restore are no-ops keyed by handle id."
  [root ledg memo memy rt manifest task step-fns goal-fns n]
  (let [handles (atom {})
        next-id (atom 0)
        acquire (fn [_ _] (let [id (str "host-sb-" (swap! next-id inc))]
                            {:id id :vm {:host? true :id id} :boot-opts {:image "host"}}))
        release (fn [_ _] 0)
        hibernate (fn [_ hnd] (let [s (str "snap_" (:id hnd))] (swap! handles assoc s hnd) s))
        restore (fn [_ s] (or (get @handles s)
                              (throw (ex-info "host-sandbox: unknown snapshot"
                                              {:snapshot-ref s}))))
        exec (fn [_vm cmd & {:keys [timeout-ms]}]
               (let [r (h/run-command ["sh" "-c" cmd] (or timeout-ms 120000))]
                 (if (or (:timeout? r) (nil? (:exit r)))
                   {:exit 1 :stdout (:out r) :stderr "timed out"}
                   {:exit (:exit r) :stdout (:out r) :stderr ""})))]
    (with-redefs [pool/acquire acquire
                  pool/release release
                  pool/hibernate hibernate
                  pool/restore restore
                  microvm/exec exec]
      (scheduler-runs! root ledg memo memy rt ::host manifest task step-fns goal-fns n))))

(defn- host-scheduler-run!
  "host-launch! for one thread."
  [root ledg memo memy rt manifest task step-fn goal-fn]
  (first (host-launch! root ledg memo memy rt manifest task [step-fn] [goal-fn] 1)))

(defn- sandbox-scheduler-run!
  "scheduler-run! over a real faraday-pool warm microVM (needs /dev/kvm)."
  [root ledg memo memy rt manifest task step-fn goal-fn]
  (let [p (pool/new-pool {:image "alpine:3.20"
                          :cache-dir (str (System/getProperty "user.home")
                                          "/.cache/jolt-microvm")
                          :max-warm 1})]
    (try
      (scheduler-run! root ledg memo memy rt p manifest task step-fn goal-fn)
      (finally (pool/close! p)))))

(defn- model-agent-deps!
  "Build faraday.agent.model/model-agent opts from the settings surface for a
  :model manifest, and fail fast when no provider resolves an api key (the same
  rule as chat and model-proposer)."
  []
  (let [cfg (config/load-config {})
        ms (config/model-step-opts cfg {} #(llm/provider %))]
    (when (empty? (:providers ms))
      (throw (ex-info "model manifest, but no model providers/roles are configured (settings.json)" {})))
    (when-not (some #(seq (:api-key (config/resolve-provider cfg % {})))
                    (keys (:providers ms)))
      (throw (ex-info (str "no LLM API key for the model agent; set "
                           "FARADAY_LLM_API_KEY or DEEPSEEK_API_KEY") {})))
    ms))

(defn run-task!
  "Run one task end to end through the honesty path. root is the project root
  (holding .faraday/harness.db); :manifest optionally overrides the task loop;
  :exec is \"shell\" (default, host exec over the scheduler+thread state machine)
  or \"sandbox\" (warm faraday-pool microVM, needs /dev/kvm). Returns the summary
  map {:task :thread-id :agent-status :hook-runs :green? :missing
  :blocking-failed :verdicts :expected-ran?}.

  Hooks fire per-cell through faraday.agent.mw/wrap-guardrails (the middleware
  spine), not turn-granular: orient/step pre+post, invoke pre (post on the next
  step), finalize pre+post, judge (when a :goal is declared). Every run lands in
  hook_runs; expected-subset-ran and blocking-subset-pass rule the turn at
  finalization."
  [root task-id & {:keys [manifest exec]}]
  (let [hd (open-db root)
        started (h/task-start hd task-id)
        hooks (:hooks-vector started)
        turn-id (str "thr_" (h/sha-hex (str task-id ":" (System/nanoTime))))
        manifest (or manifest (:manifest started) (default-task-manifest (:title started)))
        run-dir (str root "/.faraday/run")
        _ (.mkdirs (io/file run-dir))
        l (ldg/new-ledger {:path (str run-dir "/ledger.db")})
        m (memo/new-memo {:path (str run-dir "/memo")})
        memy (memory/new-memory {:path (str run-dir "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        goal-fn (when-let [g (:goal manifest)] (loop/goal-fn g))
        model? (= "model" (some-> manifest :agent :kind name))
        step-fn (mw/compile-step-fn
                 manifest
                 (cond-> {:memory memy
                          :goal-fn goal-fn
                          :hooks hooks
                          :record-hook-run! (fn [run-map] (h/record-hook-run! hd run-map))
                          :run-command h/run-command
                          :turn-id turn-id
                          :task-id task-id}
                   model? (assoc :model-agent (model-agent-deps!))))
        run! (case (or exec "shell")
               "shell" host-scheduler-run!
               "sandbox" sandbox-scheduler-run!
               (throw (ex-info "unknown --exec (want shell|sandbox)" {:exec exec})))]
    (let [out (run! root l m memy rt manifest (:title started) step-fn nil)
          _ (try (memory/extract! memy turn-id) (catch Exception _ nil))
          expected (mapv #(select-keys % [:id :gate]) hooks)
          green (h/turn-green? hd turn-id expected)
          er (h/expected-ran? hd turn-id (map :id hooks))]
      (merge {:task task-id
              :thread-id turn-id
              :agent-status (get-in out [:outcome :status])
              :agent-result (get-in out [:outcome :result])
              :hook-runs (count (h/hook-runs-for-turn hd turn-id))}
             green
             {:expected-ran? (:ok er)}))))

(defn run-manifest!
  "Launch n yieldable loops of `manifest` over host exec (no KVM) and wait for
  all. Returns {:n :outcomes :thread-ids}. No taskboard or hooks: this is the
  shell's bare loop runner for /run <manifest.edn> and /spawn <manifest.edn>
  [N]. A :model manifest resolves its provider exactly like run-task!."
  [root manifest & {:keys [task n]}]
  (let [n (or n 1)
        run-dir (str root "/.faraday/run")
        _ (.mkdirs (io/file run-dir))
        l (ldg/new-ledger {:path (str run-dir "/ledger.db")})
        m (memo/new-memo {:path (str run-dir "/memo")})
        memy (memory/new-memory {:path (str run-dir "/memory.db") :ledger l :memo m})
        rt (fanout/new-runtime {:ledger l :memo m})
        goal-fn (when-let [g (:goal manifest)] (loop/goal-fn g))
        model? (= "model" (some-> manifest :agent :kind name))
        deps (cond-> {:memory memy :goal-fn goal-fn :hooks nil :run-command h/run-command}
               model? (assoc :model-agent (model-agent-deps!)))
        step-fns (mapv (fn [_] (mw/compile-step-fn manifest deps)) (range n))
        goal-fns (vec (repeat n goal-fn))
        outcomes (host-launch! root l m memy rt manifest task step-fns goal-fns n)]
    {:n n
     :outcomes outcomes
     :thread-ids (mapv :thread-id outcomes)}))
