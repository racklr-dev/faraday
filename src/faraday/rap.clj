;; faraday-rap: RAP tool server + MCP adapter.
;; A server runs Tool Invocations (jolt-contracts SPEC.md §3) against a
;; faraday-pool, streams result frames to subscribers, and adapts them to MCP.
;; Each invocation has exactly one terminal result, keyed by call-id. invoke
;; runs asynchronously (a future), so cancel/close can short-circuit a call
;; with a terminal cancelled frame before its worker finishes. subscribe and
;; on-result may register against a call-id before it has been invoked; those
;; land in a pending map and attach when invoke creates the call.
(ns faraday.rap
  "RAP tool server and MCP adapter for the jolt control plane.

  Runs Tool Invocations (jolt-contracts SPEC.md §3) against a faraday-pool,
  streams results to subscribers, and adapts them to MCP. Each invocation has
  exactly one terminal result, keyed by call-id."
  (:refer-clojure :exclude [close])
  (:require [faraday.pool :as pool]
            [jolt.microvm :as microvm]
            [faraday.fanout :as fanout]
            [clojure.string :as str]))

(defrecord Server [vm-pool fanout policy-hook run-hook state])
(defrecord Subscription [server call-id sub-id])

;; -- helpers -----------------------------------------------------------------

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.rap/error true))))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq v))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(defn- next-id!
  "Allocate the next sub- id from the server's monotonic sub-seq."
  [state prefix]
  (let [s (swap! state update :sub-seq inc)]
    (str prefix (:sub-seq s))))

(defn- sh-quote
  "Single-quote s for POSIX sh, escaping any embedded single quotes."
  [s]
  (str "'" (str/replace s "'" "'\\''") "'"))

(defn- argv->cmd [argv]
  (str/join " " (map sh-quote argv)))

(defn- call->request
  "The Sandbox Request a Tool Invocation maps to: the request-id is the call-id,
  argv comes from the call args, and any Sandbox Request fields present in the
  args (:image :env :mounts :cpu :mem-mb :timeout-ms :egress) pass through."
  [call-id call]
  (let [args (:args call)
        argv (:argv args)]
    (when-not (and (vector? argv) (seq argv))
      (fail! "call args require a non-empty :argv vector" {:call call}))
    (merge {:v 1 :request-id call-id :argv argv}
           (select-keys args [:image :env :mounts :cpu :mem-mb :timeout-ms :egress]))))

(defn- result->segments [r]
  (let [segs (if (seq (:stdout r)) [{:type "text" :content (:stdout r)}] [])]
    (if (seq (:stderr r))
      (conj segs {:type "text" :content (:stderr r)})
      segs)))

(defn- terminal-frame [call-id status segments exit-code error]
  {:v 1 :call-id call-id :status status :segments (or segments [])
   :exit-code exit-code :error error})

(defn- cancelled-frame [call-id]
  (terminal-frame call-id "cancelled" [] nil nil))

(defn- hook-result->frame
  "Map a :run-hook result to a terminal frame. The hook is (fn request ->
  {:status :ok|:error|:denied :exit int? :stdout string? :stderr string?
   :error map?}); it owns the side effects (spawn/report), so the server only
  translates its result here. :denied maps to an error terminal with a denied
  error code, keeping the RAP envelope SPEC-faithful (ok|error|cancelled)."
  [call-id r]
  (let [status (case (:status r)
                 :ok "ok"
                 :denied "error"
                 :error "error"
                 "error")
        exit (:exit r)
        err (cond
              (= :denied (:status r))
              {:message "sandbox denied by policy or budget" :code "denied"}

              (= :error (:status r))
              {:message (or (when (string? (:error r)) (:error r))
                            (when (map? (:error r)) (:message (:error r)))
                            "sandbox error")
               :code (or (when (map? (:error r)) (:code (:error r)))
                         (some-> (:type r) name)
                         "error")}

              :else nil)]
    (terminal-frame call-id status
                    (result->segments {:stdout (:stdout r) :stderr (:stderr r)})
                    exit err)))

(defn- call-at [srv call-id]
  (get-in @(:state srv) [:calls call-id]))

(defn- claim-terminal!
  "Deliver the terminal frame for call-id exactly once. Returns true when this
  call delivered it, false when a terminal was already present."
  [srv call-id frame]
  (let [call (call-at srv call-id)
        subs (if call (:subs call) {})
        rcs (if call (:result-cbs call) {})]
    (when call
      (let [claimed? (atom false)]
        (swap! (:state srv)
               (fn [s]
                 (let [c (get-in s [:calls call-id])]
                   (if (and c (nil? (:terminal c)))
                     (do (reset! claimed? true)
                         (-> s (assoc-in [:calls call-id :terminal] frame)
                               (assoc-in [:calls call-id :subs] {})
                               (assoc-in [:calls call-id :result-cbs] {})))
                     s))))
        (when @claimed?
          (doseq [cb (vals rcs)] (try (cb frame) (catch Exception _ nil)))
          (doseq [cb (vals subs)] (try (cb frame) (catch Exception _ nil)))
          true)))))

(defn- run-invocation!
  "The async worker for one call-id. With a :run-hook the server delegates the
  acquire/exec/release/report side effects to the hook and maps its result to
  the terminal frame; otherwise it acquires a sandbox, runs the argv, releases,
  emits a report Thread Message, and delivers the terminal frame."
  [srv call-id]
  (let [{:keys [call thread-id]} (call-at srv call-id)
        hook (:run-hook srv)]
    (when call
      (if (get-in @(:state srv) [:calls call-id :cancelled?])
        (claim-terminal! srv call-id (cancelled-frame call-id))
        (let [frame (try
                      (let [request (call->request call-id call)]
                        (if hook
                          (hook-result->frame call-id (hook request))
                          (let [h (pool/acquire (:vm-pool srv) request)]
                            (try
                              (let [r (microvm/exec (:vm h)
                                                    (argv->cmd (get-in call [:args :argv]))
                                                    :timeout-ms (or (:timeout-ms request) 300000))
                                    segs (result->segments r)
                                    ok? (zero? (:exit r))]
                                (terminal-frame call-id (if ok? "ok" "error") segs (:exit r)
                                                (when-not ok? {:message (str "command exited " (:exit r)) :code "exit"})))
                              (finally (pool/release (:vm-pool srv) h))))))
                      (catch Exception e
                        (terminal-frame call-id "error" [] nil
                                        {:message (or (ex-message e) "invocation failed")})))]
          (if (get-in @(:state srv) [:calls call-id :cancelled?])
            (do (when-not hook
                  (try (fanout/report (:fanout srv) thread-id "cancelled" nil []) (catch Exception _ nil)))
                (claim-terminal! srv call-id (cancelled-frame call-id)))
            (do (when-not hook
                  (try (fanout/report (:fanout srv) thread-id (:status frame)
                                      {:exit-code (:exit-code frame)} (:segments frame))
                       (catch Exception _ nil)))
                (claim-terminal! srv call-id frame))))))))

;; -- public API --------------------------------------------------------------

(defn new-server
  "Open a tool server over opts, a map with :fanout (faraday-fanout runtime) and
  either :vm-pool (faraday-pool handle) or :run-hook (fn request -> {:status
  :ok|:error|:denied :exit int? :stdout string? :stderr string? :error map?}),
  plus an optional :policy-hook (fn call -> truthy to permit, nil = always
  permit). When :run-hook is given it replaces the acquire/exec/release path
  and owns the Thread Message reporting; the server only maps its result to the
  terminal frame. Returns an opaque server handle."
  [opts]
  (let [vp (:vm-pool opts)
        rt (:fanout opts)
        hook (:run-hook opts)]
    (when-not (or vp hook)
      (fail! "new-server requires a :vm-pool or a :run-hook" {:opts opts}))
    (when-not rt
      (fail! "new-server requires a :fanout" {:opts opts}))
    (->Server vp rt (:policy-hook opts) hook (atom {:calls {} :pending {} :sub-seq 0}))))

(defn invoke
  "Start the Tool Invocation call (SPEC.md §3). Assigns a call-id if absent and
  streams result frames to subscribers as it runs. Returns the call-id."
  [srv call]
  (when-not (map? call)
    (fail! "call must be a map" {:call call}))
  (non-empty-string! (:thread-id call) :thread-id)
  (non-empty-string! (:name call) :name)
  (let [args (:args call)]
    (when-not (map? args)
      (fail! "call requires an :args map" {:call call}))
    (when-not (and (vector? (:argv args)) (seq (:argv args)))
      (fail! "call args require a non-empty :argv vector" {:call call})))
  (when-let [hook (:policy-hook srv)]
    (when-not (hook call)
      (fail! "policy vetoed invocation" {:type :policy-vetoed :call call})))
  (let [call-id (or (:call-id call) (str "call_" (java.util.UUID/randomUUID)))
        call (assoc call :call-id call-id)
        state (:state srv)]
    (swap! state (fn [s]
                   (let [pending (get-in s [:pending call-id])]
                     (-> s
                         (update :pending dissoc call-id)
                         (assoc-in [:calls call-id]
                                   {:call call :thread-id (:thread-id call)
                                    :subs (or (:subs pending) {})
                                    :result-cbs (or (:result-cbs pending) {})
                                    :terminal nil :cancelled? false})))))
    (future (run-invocation! srv call-id))
    call-id))

(defn cancel
  "Cancel the invocation with call-id, emitting its terminal cancelled result."
  [srv call-id]
  (when-not (call-at srv call-id)
    (fail! "unknown call-id" {:call-id call-id}))
  (swap! (:state srv) assoc-in [:calls call-id :cancelled?] true)
  call-id)

(defn close
  "Close the invocation (or thread) identified by id, emitting a terminal close."
  [srv id]
  (let [calls (get-in @(:state srv) [:calls])]
    (if (contains? calls id)
      (do (cancel srv id) :closed)
      (do (doseq [[cid {:keys [thread-id]}] calls
                  :when (= id thread-id)]
            (cancel srv cid))
          :closed))))

(defn- register!
  "Register cb under path (subs or result-cbs) for call-id, or stash it in the
  pending map when the call has not been invoked yet. Returns the id."
  [srv call-id cb path]
  (let [sub-id (next-id! (:state srv) "sub-")]
    (if (call-at srv call-id)
      (do (swap! (:state srv) assoc-in [:calls call-id path sub-id] cb)
          (when-let [t (:terminal (call-at srv call-id))]
            (cb t)
            (swap! (:state srv) update-in [:calls call-id path] dissoc sub-id))
          sub-id)
      (do (swap! (:state srv) assoc-in [:pending call-id path sub-id] cb)
          sub-id))))

(defn subscribe
  "Register a subscriber for one invocation. cb is called with each result frame
  as it streams; the terminal result is the last call. Returns a subscription
  handle that close!-on-the-handle releases."
  [srv call-id cb]
  (let [call (call-at srv call-id)]
    (if (and call (:terminal call))
      (do (cb (:terminal call))
          (->Subscription srv call-id nil))
      (->Subscription srv call-id (register! srv call-id cb :subs)))))

(defn on-result
  "Register cb to be called once with the terminal result of call-id."
  [srv call-id cb]
  (let [call (call-at srv call-id)]
    (if (and call (:terminal call))
      (do (cb (:terminal call))
          (->Subscription srv call-id nil))
      (->Subscription srv call-id (register! srv call-id cb :result-cbs)))))

(defn close!
  "Stop the server and release subscriptions (does not close vm-pool/fanout).
  A subscription handle passed here releases just that one subscription."
  [srv]
  (if (instance? Subscription srv)
    (let [state (:state (:server srv))
          cid (:call-id srv)
          sub-id (:sub-id srv)]
      (when sub-id
        (swap! state (fn [s] (-> s
                                 (update-in [:calls cid :subs] dissoc sub-id)
                                 (update-in [:calls cid :result-cbs] dissoc sub-id)
                                 (update-in [:pending cid :subs] dissoc sub-id)
                                 (update-in [:pending cid :result-cbs] dissoc sub-id)))))
      nil)
    (do (reset! (:state srv) {:calls {} :pending {} :sub-seq 0})
        nil)))
