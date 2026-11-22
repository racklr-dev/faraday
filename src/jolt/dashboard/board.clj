;; The single source of truth for the UI: one glimmer ratom, resampled
;; from the live plane every tick. wrap-datastar subscribes each SSE
;; connection to the cells its last render dereffed, so a swap! here is all
;; it takes to push the change to every open tab (batched per tick).
(ns jolt.dashboard.board
  (:require [glimmer.ratom :as ratom]
            [jolt.plane :as plane]))

(def board
  "The board: plane stats, pool shape, and a heartbeat that proves SSE
  liveness. pool/memo/calls/sandboxes land in M1+."
  (ratom/atom {:plane {} :pool {:warm 0 :total 0} :heartbeat 0}))

(defn sample!
  "Read live plane state into the board. pool/stats, memo/stats and the rap
  call map join here as the dashboard grows."
  [{:keys [plane]}]
  (swap! board assoc
         :plane (try (plane/stats plane) (catch Exception _ {}))
         :heartbeat (System/currentTimeMillis)))

(defn start-sampler!
  "Sample every interval-ms on a thread until the returned stop fn runs."
  [{:keys [plane interval-ms] :or {interval-ms 250}}]
  (let [stop? (atom false)]
    (future
      (loop []
        (when-not @stop?
          (sample! {:plane plane})
          (Thread/sleep interval-ms)
          (recur))))
    (fn [] (reset! stop? true))))
