;; The single source of truth for the UI: one glimmer ratom holding the full
;; control-plane model. wrap-datastar subscribes each SSE connection to the
;; cells its last render dereffed, so a swap! here pushes the change to every
;; open tab (batched per tick).
(ns jolt.dashboard.board
  (:require [clojure.string :as str]
            [glimmer.ratom :as ratom]
            [jolt.dashboard.runtime :as rt]))

(def board
  (ratom/atom {:heartbeat 0
               :plane {:budget {:cpu 4 :mem-mb 4096}
                       :used {:cpu 0 :mem-mb 0} :granted 0 :waiting 0}
               :pool {:warm 0 :total 0 :snapshots 0 :sandboxes []}
               :gate [] :runs [] :calls []
               :policies [] :egress [] :image "" :notice nil
               :dry-result nil :thread-detail nil}))

(defn sample!
  "Read live counters (plane stats, pool, RAP calls) into the board."
  [runtime]
  (swap! board assoc
         :heartbeat (System/currentTimeMillis)
         :plane (rt/plane-stats runtime)
         :pool (assoc (rt/pool-stats runtime) :sandboxes (rt/list-sandboxes runtime))
         :calls (rt/list-calls runtime)))

(defn seed-config!
  "One-time: put the static policy/egress/image config into the board."
  [runtime]
  (swap! board assoc
         :policies (vec (:policies runtime))
         :egress (mapv val (sort-by key (:egress runtime)))
         :image (:image runtime)))

(defn show-dry-result!
  "Persist a simulator decision so the live Policy view keeps rendering it."
  [d]
  (swap! board assoc :dry-result d))

(defn show-thread!
  "Persist the selected thread + messages so the live Threads view keeps them."
  [tid msgs]
  (swap! board assoc :thread-detail {:thread-id tid :msgs msgs}))

(defn- decision-for [result]
  (case (:status result)
    :ok :allow
    :denied :deny
    :error :error
    :allow))

(defn record-run!
  "Called from the runtime's run-hook when a run reaches its terminal frame:
  append a gate decision row and a run row so Runs/Overview/Threads show it."
  [call result]
  (let [args (:args call)
        agent (or (:owner call) (:thread-id call) "?")
        now (System/currentTimeMillis)
        status (:status result)
        egress-ref (let [eg (:egress args)]
                     (cond (string? eg) eg
                           (map? eg) (or (:ref eg) (:egress-id eg) "inline")
                           :else "none"))
        gate-row {:request-id (or (:request-id result) (:call-id call))
                  :principal agent
                  :action "RunSandbox"
                  :resource (or (:image args) "?")
                  :decision (name (decision-for result))
                  :ts now}
        run-row {:request-id (or (:request-id result) (:call-id call))
                 :thread-id (:thread-id result)
                 :agent agent
                 :tool (or (:name call) "run_shell")
                 :argv (str/join " " (or (:argv args) []))
                 :egress egress-ref
                 :status (name status)
                 :exit (:exit result)
                 :stdout (:stdout result)
                 :error (or (get-in result [:error :message])
                            (:error result)
                            (when (:type result) (name (:type result))))
                 :ts now}]
    (swap! board
      (fn [b]
        (-> b
            (update :gate (fn [g] (take 30 (cons gate-row g))))
            (update :runs (fn [rs] (take 60 (cons run-row rs))))
            (assoc :notice (when (= status :denied)
                             (str "DENIED  " agent "  egress=" egress-ref))))))))

(defn start-sampler!
  "Sample every interval-ms on a thread until the returned stop fn runs."
  [runtime interval-ms]
  (let [stop? (atom false)]
    (future
      (loop []
        (when-not @stop?
          (sample! runtime)
          (Thread/sleep interval-ms)
          (recur))))
    (fn [] (reset! stop? true))))
