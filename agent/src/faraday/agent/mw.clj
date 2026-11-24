;; faraday.agent.mw — the nREPL middleware spine.
;; The loop-as-data manifest compiles to a middleware chain; each wrap-* is one
;; middleware, the same (fn [handler] (fn [request] ...)) shape jolt.nrepl builds
;; at load. Here the request IS the turn context, and the base handler is the
;; agent step-fn (script or model), so one chain runs unchanged over both the
;; synchronous faraday.agent/run! loop and the faraday.agent.yield state machine.
;;
;; The wrappers are pure over their opts and only call seams they are handed:
;; wrap-memory uses a faraday-memory handle, wrap-router/repair use
;; faraday.agent.model, wrap-goal/wrap-guardrails use a shared :fire! engine the
;; harness composes from its hook_runs ledger + command runner. faraday.agent.mw
;; never reaches into faraday.harness, so the dependency direction stays
;; harness -> agent.
(ns faraday.agent.mw
  "The nREPL middleware spine: a manifest + deps compiles to a step-fn chain.

  Each wrap-* is one middleware of the ROADMAP stack:

    wrap-history    window the transcript to the context budget
    wrap-memory     inject hot memories inline; index the thread once
    wrap-router     realize a routed model step (repair/escalate/breaker)
    wrap-repair     validate the handler's action shape (honest error on bad)
    wrap-permission one decision point, op-based rules, a why trace
    wrap-goal       the independent judge at finalization
    wrap-guardrails run the scope's per-cell pre/post hooks; every run lands
                    in hook_runs; expected-subset-ran at finalization
    wrap-taskboard  (compile-time) resolve a task's scope into loop-as-data

  compile-step-fn assembles them around the base step-fn the manifest declares."
  (:require [clojure.edn :as edn]
            [faraday.agent.loop :as loop]
            [faraday.agent.model :as model]
            [faraday.memory :as memory]))

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.mw/error true))))

(defn- sha-hex
  "Lowercase hex SHA-256 of s (a string) — same convention as the harness DB."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map (fn [b] (format "%02x" (bit-and (int b) 0xff))) bs))))

;; -- the base handler ---------------------------------------------------------

(defn- base-step-fn
  "The innermost handler: the manifest's :agent spec realized as a step-fn.
  :script -> loop/manifest-agent (data-only). :model -> realized by
  wrap-router from deps :model-agent; until then it returns an honest error."
  [manifest deps]
  (let [kind (some-> manifest :agent :kind name)]
    (if (= "model" kind)
      (if (:model-agent deps)
        (fn [_ctx] {:done {:error :model-router-not-applied}})
        (fail! "manifest :agent :kind model requires deps :model-agent"
               {:agent (:agent manifest)}))
      (loop/manifest-agent manifest))))

;; -- wrap-history -------------------------------------------------------------

(defn wrap-history
  "Window the transcript to the context budget: keep the task (the first
  entry) and the last entries, so a long transcript never overflows the model
  context. opts: :window (max entries kept, task included; default 16)."
  [opts]
  (fn [handler]
    (let [k (or (:window opts) 16)]
      (fn [ctx]
        (let [t (:transcript ctx)
              n (count t)]
          (handler (if (and k (> n k))
                     (assoc ctx :transcript
                            (into [(first t)] (take-last (dec k) t)))
                     ctx)))))))

;; -- wrap-compress ------------------------------------------------------------

(defn default-summarize
  "Deterministic middle fold when no :summarize seam is given: the count and
  the role frequency of the folded entries. A model summarizer is injected the
  same way (opts :summarize (fn [middle] -> value))."
  [middle]
  {:count (count middle)
   :kinds (frequencies (map :role middle))})

(defn wrap-compress
  "Fold the transcript middle into a structured summary at a threshold, so a
  long history compresses rather than overflows. opts: :compress-threshold
  (default 24; entries beyond the threshold have their middle folded),
  :summarize (fn [middle] -> value; default default-summarize). Runs inside
  wrap-history, so it engages only when the history window is raised above the
  threshold."
  [opts]
  (fn [handler]
    (let [th (or (:compress-threshold opts) 24)
          summarize (or (:summarize opts) default-summarize)]
      (fn [ctx]
        (let [t (:transcript ctx)
              n (count t)]
          (handler (if (and th (> n th))
                     (assoc ctx :transcript
                            (vec (concat [(first t)]
                                         [{:role :compressed
                                           :content (summarize (vec (butlast (rest t))))}]
                                         [(peek t)])))
                     ctx)))))))

;; -- wrap-memory --------------------------------------------------------------

(defn wrap-memory
  "Inject hot memories inline and demote the rest to a breadcrumb index.
  opts: :memory (faraday-memory handle). On the orient step it indexes the
  thread once (ledger+memo -> FTS5), then every step carries :salient and
  :breadcrumbs for the model to read. Never throws: a missing/closed memory
  passes the context through."
  [opts]
  (fn [handler]
    (let [mem (:memory opts)
          indexed? (atom false)]
      (fn [ctx]
        (let [tid (:thread-id ctx)]
          (when (and mem tid (not @indexed?))
            (try (memory/index-thread! mem tid) (catch Exception _ nil))
            (reset! indexed? true))
          (handler (if (and mem tid)
                     (assoc ctx
                            :salient (try (memory/salient mem tid) (catch Exception _ nil))
                            :breadcrumbs (try (memory/breadcrumbs mem tid) (catch Exception _ nil)))
                     ctx)))))))

;; -- wrap-router --------------------------------------------------------------

(defn wrap-router
  "Realize a routed model step from :model-agent opts (faraday.agent.model
  opts: :providers/:role-config/:escalation-ladder). With no :model-agent the
  handler passes through unchanged (script manifests route nowhere)."
  [opts]
  (fn [handler]
    (if-let [mo (:model-agent opts)]
      (let [m (model/model-agent mo)]
        (fn [ctx] (m ctx)))
      handler)))

;; -- wrap-repair --------------------------------------------------------------

(defn wrap-repair
  "Validate the handler's action shape; on invalid return an honest
  {:done {:error :step-invalid}} instead of letting the loop treat it as an
  invalid action. The repair->escalate->circuit-break ladder itself lives in
  faraday.agent.model (wrap-router), so a weak model is repaired before this
  sees the action."
  [_opts]
  (fn [handler]
    (fn [ctx]
      (let [a (handler ctx)]
        (if (and (map? a) (model/valid-action? a))
          a
          {:done {:error :step-invalid :raw (pr-str a)}})))))

;; -- wrap-permission ----------------------------------------------------------

(defn- action-op [action]
  (cond
    (contains? action :done) :done
    (contains? action :tool-call) :tool-call
    (contains? action :tool-calls) :tool-call
    (contains? action :skip) :skip
    :else :unknown))

(defn- action-name [action]
  (or (get-in action [:tool-call :name])
      (some->> (get action :tool-calls) first :name)
      (get-in action [:done :name])
      (get-in action [:skip :name])))

(defn rule-decide
  "Default op-based permission: rules is [{:op :tool-call|:done|:skip|:unknown
  :name exact-name-or-regex :allow? bool :why string}]. First matching rule
  wins; no match allows with :default-allow. One decision point, one why."
  [rules]
  (fn [_ctx action]
    (let [op (action-op action)
          nm (action-name action)
          match? (fn [r]
                   (and (= op (:op r))
                        (or (nil? (:name r))
                            (= nm (:name r))
                            (and (instance? java.util.regex.Pattern (:name r))
                                 (re-find (:name r) (str nm))))))]
      (or (some (fn [r]
                  (when (match? r)
                    {:allow? (boolean (:allow? r))
                     :why (or (:why r) (pr-str r))}))
                rules)
          {:allow? true :why "default-allow"}))))

(defn wrap-permission
  "One decision point before the step returns. opts: :decide (fn [ctx action]
  -> {:allow? bool :why s}), default rule-decide over :rules. A denial
  returns {:done {:error :denied :why ..}}, so the loop stops honestly."
  [opts]
  (fn [handler]
    (let [decide (or (:decide opts)
                     (when (:rules opts) (rule-decide (:rules opts)))
                     (fn [_ _] {:allow? true :why "default-allow"}))]
      (fn [ctx]
        (let [a (handler ctx)
              d (try (decide ctx a)
                     (catch Exception e {:allow? false :why (str "decide threw: " (ex-message e))}))]
          (if (:allow? d)
            a
            {:done {:error :denied :why (:why d) :raw-action a}}))))))

;; -- wrap-goal ----------------------------------------------------------------

(defn wrap-goal
  "The independent judge. With a :goal-fn it rules BEFORE each step: met stops
  the loop as {:done {:result :goal-met :goal-met true}} (firing the judge cell's
  hooks), so a goal-met stop still finalizes through the guardrails. When the
  agent itself returns :done, the judge annotates the unmet verdict. Bounded by
  the loop's max-turns. With no :goal-fn it passes through."
  [opts]
  (fn [handler]
    (let [goal (:goal-fn opts)
          fire! (:fire! opts)
          judge! (fn [ctx]
                   (let [pre (when fire! (fire! :judge :pre))
                         met (boolean (goal ctx))
                         _ (when fire! (fire! :judge :post))]
                     {:met met :blocked? (:blocked? pre) :vetoes (:vetoes pre)}))]
      (fn [ctx]
        (if-not goal
          (handler ctx)
          (let [j (judge! ctx)]
            (cond
              (:blocked? j)
              {:done {:error :guardrail-blocked :cell :judge :vetoes (:vetoes j)}}

              (:met j)
              {:done {:result :goal-met :goal-met true :goal-judged true}}

              :else
              (let [a (handler ctx)]
                (if (and (map? a) (contains? a :done))
                  (update a :done merge {:goal-met false :goal-judged true})
                  a)))))))))

;; -- wrap-guardrails ----------------------------------------------------------

(defn previous-cell
  "The cell that just completed before this step: :invoke when the last
  transcript entry is a tool result, :step otherwise (turns>0). nil at orient."
  [ctx]
  (when (pos? (or (:turns ctx) 0))
    (if (= :tool-result (:role (peek (:transcript ctx))))
      :invoke
      :step)))

(defn current-cell
  "The cell this step is about to do: :orient on the first step, else :step."
  [ctx]
  (if (zero? (or (:turns ctx) 0)) :orient :step))

(defn make-fire!
  "Build the per-cell hook engine. opts: :hooks (resolved hooks-vector),
  :record! (fn run-map -> nil), :run-command (fn argv timeout -> {:exit :out
  :timeout?}), :turn-id, :task-id. Returns (fn [cell position] -> {:blocked?
  bool :vetoes [..] :runs [..]}). A command hook runs out-of-process; a
  non-command hook is recorded honestly as :skip, so it can never block."
  [opts]
  (let [hooks (or (:hooks opts) [])
        record! (or (:record! opts) (fn [_] nil))
        run-command (or (:run-command opts)
                        (fn [_ _] {:exit 1 :out "no run-command seam" :timeout? false}))
        turn-id (or (:turn-id opts) "thr_unknown")
        task-id (:task-id opts)]
    (fn [cell position]
      (let [matching (filter (fn [hk]
                               (and (= position (keyword (:position hk)))
                                    (or (= "*" (:cell hk))
                                        (= (name cell) (:cell hk)))))
                             hooks)
            runs (mapv (fn [hk]
                         (let [how (:how hk)
                               cmd (:command how)
                               timeout (or (:timeout-ms how) (:timeout_ms hk) 8000)
                               r (if (and (vector? cmd) (seq cmd))
                                   (let [t0 (System/nanoTime)
                                         rr (try (run-command cmd timeout)
                                                 (catch Exception e
                                                   {:exit 1 :out (str "hook run threw: " (ex-message e)) :timeout? false}))
                                         ms (long (/ (- (System/nanoTime) t0) 1000000.0))]
                                     (assoc rr
                                            :verdict (cond (:timeout? rr) :timeout
                                                           (zero? (:exit rr)) :pass
                                                           :else :fail)
                                            :ms ms))
                                   {:verdict :skip
                                    :exit nil
                                    :out (str "hook " (:id hk) " (" (:kind hk) ") is not cell-granular")
                                    :ms 0})
                               run-map {:hook-id (:id hk)
                                        :task-id task-id
                                        :turn-id turn-id
                                        :cell (name cell)
                                        :position (name position)
                                        :verdict (:verdict r)
                                        :exit-code (:exit r)
                                        :output-sha (sha-hex (str (:out r)))
                                        :output-ms (:ms r)}]
                           (record! run-map)
                           {:hook hk :run r :run-map run-map}))
                       matching)
            vetoes (mapv (fn [r]
                           (select-keys (:hook r) [:id :kind :gate :why :cell :position]))
                         (filter (fn [r]
                                   (and (= "blocking" (get-in r [:hook :gate]))
                                        (not= :pass (get-in r [:run :verdict]))))
                                 runs))]
        {:blocked? (boolean (seq vetoes))
         :vetoes vetoes
         :runs runs}))))

(defn wrap-guardrails
  "Run the scope's per-cell pre/post hooks around each cell of the step.
  opts: :fire! (fn [cell position] -> {:blocked? :vetoes}). Outermost wrapper,
  so orient/step pre fire before the inner chain and invoke/finalize post fire
  after it. A blocking hook that fails vetoes the step with an honest error
  done naming the vetoes."
  [opts]
  (fn [handler]
    (let [fire! (:fire! opts)]
      (if-not fire!
        handler
        (fn [ctx]
          ;; post for the cell that just completed (a tool result landed)
          (when (= :invoke (previous-cell ctx))
            (fire! :invoke :post))
          (let [curr (current-cell ctx)
                pre (fire! curr :pre)]
            (if (:blocked? pre)
              {:done {:error :guardrail-blocked :cell curr :vetoes (:vetoes pre)}}
              (let [a (handler ctx)
                    _ (fire! curr :post)
                    cell (cond (contains? a :done) :finalize
                               (contains? a :tool-call) :invoke
                               (contains? a :tool-calls) :invoke
                               :else :step)
                    pre2 (fire! cell :pre)]
                (if (:blocked? pre2)
                  {:done {:error :guardrail-blocked :cell cell :vetoes (:vetoes pre2)}}
                  (do (when (= :finalize cell) (fire! cell :post))
                      a))))))))))

;; -- wrap-taskboard (compile-time) ---------------------------------------------

(defn resolve-task
  "Resolve a task into loop-as-data via :task-start-fn (fn [task-id] ->
  faraday.harness/task-start). Returns {:manifest effective-manifest :hooks
  hooks-vector :task task-id :title title}. The task's own :manifest wins over
  the project :default-manifest (an EDN string)."
  [opts]
  (let [f (:task-start-fn opts)
        tid (:task-id opts)]
    (when-not (and f tid)
      (fail! "resolve-task requires :task-start-fn and :task-id" opts))
    (let [ts (f tid)
          m (or (:manifest ts)
                (when-let [ds (:default-manifest ts)]
                  (try (edn/read-string ds) (catch Exception _ nil))))]
      {:manifest m
       :hooks (:hooks-vector ts)
       :task tid
       :title (:title ts)})))

;; -- the chain ----------------------------------------------------------------

(def default-stack
  "The ROADMAP order, applied innermost-first: history, compress, memory,
  router, repair, permission, goal, guardrails (outermost)."
  [wrap-history wrap-compress wrap-memory wrap-router wrap-repair wrap-permission
   wrap-goal wrap-guardrails])

(defn compile-step-fn
  "Compile a manifest + deps into the middleware-wrapped step-fn for run!/yield.

  deps: {:memory faraday-memory, :goal-fn predicate, :model-agent model-agent
         opts, :hooks resolved hooks-vector, :record-hook-run! fn, :run-command
         fn, :turn-id s, :task-id s, :decide fn, :rules [...]}
  opts: :stack (default default-stack), :window, :task-start-fn, :task-id.

  When :task-start-fn + :task-id are given, resolve-task supplies the manifest
  and hooks (wrap-taskboard), splicing the task's manifest over the project
  default. Returns (fn [ctx] action)."
  [manifest deps & {:as opts}]
  (let [resolved (when (and (:task-id opts) (:task-start-fn opts))
                   (resolve-task opts))
        m (or (:manifest resolved) manifest)
        hooks (or (:hooks resolved) (:hooks deps))
        fire! (when (seq hooks)
                (make-fire! {:hooks hooks
                             :record! (or (:record! deps) (:record-hook-run! deps))
                             :run-command (:run-command deps)
                             :turn-id (:turn-id deps)
                             :task-id (:task-id deps)}))
        model-agent (merge (:model-agent deps) (:model (:agent m)))
        wrap-opts (merge deps opts {:fire! fire! :hooks hooks
                                    :model-agent (when (seq model-agent) model-agent)})
        base (base-step-fn m deps)
        step (reduce (fn [h w] ((w wrap-opts) h)) base (or (:stack opts) default-stack))]
    step))
