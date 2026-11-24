;; faraday.agent — the agent framework for a jolt-based control plane.
;; An agent is a step fn of the context; the framework executes every tool-call
;; it returns as a Tool Invocation through faraday-rap (SPEC sec 3), waits for
;; the single terminal frame, appends call+result to the transcript, and loops
;; until the agent says done, the turn budget is spent, or a step errors.
(ns faraday.agent
  "Agent framework: drive an agent's tool-calling loop over a faraday-rap server.

  An agent is a step function of one argument, the context:

      (fn [ctx] action)

  ctx is a map with :task (the initial user task, when supplied), :transcript
  (the ordered entries so far) and :turns (tool-calls completed so far).
  action is a map carrying exactly one of two keys:

      {:tool-call {:name run_shell :args {:argv [...]} :display-as ...}}
      {:done      ...}

  The framework executes each tool-call as a Tool Invocation through faraday-rap
  (SPEC sec 3), waits for its single terminal frame, appends the call and its
  result to the transcript, and loops. A tool-call whose invoke throws (bad
  args, policy veto) becomes an error tool-result so the agent can recover."
  (:refer-clojure :exclude [run!])
  (:require [faraday.rap :as rap]
            [faraday.contracts :as contracts]))

;; -- helpers -----------------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent/error true))))

(defn- new-thread-id []
  (str "thr_" (java.util.UUID/randomUUID)))

(defn warm-jdk-providers!
  "Force jolt to install its lazy JDK class providers single-threaded, so
  futures spawned afterwards do not race on first use. Call this before
  running concurrent agents or sandbox streams; it touches the JDK classes
  the fanout/ledger path uses (java.time.Instant, java.util.UUID,
  java.io.File)."
  []
  (str (java.time.Instant/now))
  (str (java.util.UUID/randomUUID))
  (str (java.io.File. (System/getProperty "java.io.tmpdir")))
  nil)

(defn- append-entry!
  "Append an entry to the transcript, invoking the on-step hook when present."
  [ctx entry on-step]
  (when on-step (try (on-step entry) (catch Exception _ nil)))
  (update ctx :transcript conj entry))

(defn- invoke!
  "Invoke one tool-call on the rap server and await its terminal frame.
  Returns {:frame frame :call-id id} on completion, {:timeout true :call-id id}
  when the terminal did not land in time, or {:invoke-error ex} when invoke threw."
  [srv thread-id call timeout-ms on-frame frames]
  (let [cid (try
              (rap/invoke srv (assoc call :thread-id thread-id))
              (catch Exception e {:invoke-error e}))]
    (if (:invoke-error cid)
      {:invoke-error (:invoke-error cid)}
      (let [p (promise)
            _ (rap/on-result srv cid
                             (fn [frame]
                               (swap! frames conj frame)
                               (when on-frame (try (on-frame frame) (catch Exception _ nil)))
                               (deliver p frame)))
            frame (deref p timeout-ms ::timeout)]
        (if (= ::timeout frame)
          (do (try (rap/cancel srv cid) (catch Exception _ nil))
              {:timeout true :call-id cid})
          {:frame frame :call-id cid})))))

(defn- result-entry
  "Shape the transcript tool-result entry from an invoke! outcome."
  [call r]
  (let [frame (:frame r)
        status (cond
                 (:invoke-error r) "error"
                 (:timeout r) "cancelled"
                 :else (:status frame))]
    (merge {:role :tool-result
            :call-id (:call-id r)
            :name (:name call)
            :status status}
           (when (:invoke-error r)
             {:error {:message (or (ex-message (:invoke-error r)) "invoke failed")
                      :type (get (ex-data (:invoke-error r)) :type)}})
           (when (:timeout r) {:error {:message "timed out waiting for terminal"
                                       :code "timeout"}})
           (when (map? frame)
             (let [res (contracts/decode! :tool-result frame)]
               {:exit-code (:exit-code res)
                :segments (:segments res)
                :error (:error res)
                :frame frame})))))

;; -- scripted agent ----------------------------------------------------------

(defn scripted-agent
  "A deterministic agent that replays a fixed script of actions in order. Each
  entry is either a tool-call map or a {:done ...} map. After the last entry it
  returns {:done {:result :script-exhausted}}. Useful for tests and for driving
  a known sequence of sandbox execs without a model."
  [actions]
  (let [i (atom 0)]
    (fn [_ctx]
      (let [n (swap! i inc)]
        (if (<= n (count actions))
          (nth actions (dec n))
          {:done {:result :script-exhausted}})))))

;; -- the loop ----------------------------------------------------------------

(defn run!
  "Drive an agent through its tool-calling loop over a faraday-rap server.

  opts keys:
    :rap       faraday-rap server handle (required)
    :agent     step fn: (fn [ctx] action) (required)
    :task      optional initial user task string
    :thread-id optional owning thread id (default a fresh thr_)
    :max-turns cap on tool-calls executed (default 32)
    :timeout-ms per-invocation terminal wait (default 120000)
    :goal      optional predicate (fn [ctx] -> truthy): when it passes, the
               loop stops at once with {:status :done :met true :stop-reason
               :goal}. A command goal is the same shape — a predicate that
               runs the command and returns whether it passed.
    :on-frame  fn called with every frame delivered by rap
    :on-step   fn called with each transcript entry as it is appended

  Returns {:status :done|:max-turns|:error :result .. :transcript .. :turns n
            :frames .. :met true|false (present only when :goal was given)}."
  [opts]
  (let [srv (:rap opts)
        f (:agent opts)
        goal (:goal opts)]
    (when-not srv (fail! "run! requires a :rap server" {:opts opts}))
    (when-not (ifn? f) (fail! "run! requires an :agent step fn" {:opts opts}))
    (when (and goal (not (ifn? goal)))
      (fail! "run! :goal must be a predicate fn of ctx" {:goal goal}))
    (let [thread-id (or (:thread-id opts) (new-thread-id))
          max-turns (or (:max-turns opts) 32)
          timeout-ms (or (:timeout-ms opts) 120000)
          on-frame (:on-frame opts)
          on-step (:on-step opts)
          frames (atom [])
          ctx0 (if (:task opts)
                 {:task (:task opts)
                  :transcript [{:role :user :content (:task opts)}]
                  :turns 0}
                 {:transcript [] :turns 0})
          ctx (atom ctx0)
          out (fn [status m turns]
                (merge {:status status
                        :transcript (:transcript @ctx)
                        :turns turns
                        :frames @frames}
                       m
                       (when (and goal (not (contains? m :met))) {:met false})))]
      (loop []
        (if (and goal (goal @ctx))
          (out :done {:stop-reason :goal :result {:goal-met true} :met true} (:turns @ctx))
          (let [{:keys [turns]} @ctx
                action (try (f @ctx) (catch Exception e {:step-error e}))]
            (cond
              ;; agent finished
              (contains? action :done)
              (let [entry {:role :assistant :content (:done action)}]
                (swap! ctx append-entry! entry on-step)
                (out :done {:stop-reason :agent :result (:done action)} turns))

              ;; agent wants one more tool invocation
              (contains? action :tool-call)
              (if (>= turns max-turns)
                (out :max-turns {} turns)
                (let [call (:tool-call action)
                      call-entry {:role :tool-call :name (:name call) :args (:args call)
                                  :display-as (:display-as call)}
                      r (invoke! srv thread-id call timeout-ms on-frame frames)
                      res-entry (result-entry call r)]
                  (swap! ctx append-entry! call-entry on-step)
                  (swap! ctx append-entry! res-entry on-step)
                  (swap! ctx update :turns inc)
                  (recur)))

              ;; step threw
              (contains? action :step-error)
              (out :error {:error (or (ex-message (:step-error action)) "step failed")} turns)

              ;; invalid action shape
              :else
              (out :error {:error (str "agent step returned an invalid action: " (pr-str action))} turns))))))))

(defn run-all!
  "Run N agent tasks concurrently, one agent per sandbox exec stream, over the
  same rap server. specs is a vector of run! opt maps; each needs :agent, and
  :rap defaults to srv. Returns a vector of outcomes in spec order."
  [srv specs]
  (warm-jdk-providers!)
  (let [fs (doall (map (fn [spec]
                         (future (run! (assoc spec :rap (or (:rap spec) srv))))) specs))]
    (mapv deref fs)))
