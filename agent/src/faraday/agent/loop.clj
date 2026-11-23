;; faraday-agent loop-as-data: the turn loop is an EDN manifest in memo.
;; A loop manifest is the whole turn loop as plain data: the agent spec (a
;; script of actions, or a model+provider role), the run budget, the goal
;; stop-condition, and the soak gate. It lives content-addressed in a
;; faraday-memo; the agent rewrites it as data; the plane validates it against
;; the declared loop-manifest contract; the pool soaks N runs against a test
;; harness; and the ledger re-points at the new memo key only when the soak
;; passes, rolling back to the previous key otherwise.
(ns faraday.agent.loop
  "Loop-as-data: the turn loop is an EDN manifest, validated and soak-gated.

  The loop manifest declares the loop ONCE as data — the agent spec, run
  budget, goal stop-condition and soak count — and is stored content-addressed
  in a faraday-memo. promote-loop! is the gate: it validates the candidate
  against the loop-manifest contract, soaks N runs against a harness over the
  pool, and only then re-points the ledger at the new memo key. A failed soak
  rolls the ledger pointer back to the previous key."
  (:require [faraday.agent :as agent]
            [faraday.contracts :as contracts :refer [define-contract]]
            [faraday.memo :as memo]
            [faraday.ledger :as ledger]))

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.agent.loop/error true))))

(defn- non-nil [m]
  (into {} (remove (fn [[_ v]] (nil? v)) m)))

;; -- the loop manifest as a declared contract --------------------------------

(define-contract agent-spec
  (:kind :string :required true :enum #{"script"}
   :doc "how to realize the agent from data; :script -> scripted-agent")
  (:script :array :required true :of :any
   :doc "ordered actions: {:tool-call ..} or {:done ..}"))

(define-contract soak-spec
  (:n :integer :required true :doc "number of soak runs that must all pass"))

(define-contract loop-manifest
  (:v :integer :required true :doc "always 1")
  (:loop-id :string :required true :doc "stable loop identity")
  (:name :string :required true :doc "human-readable loop name")
  (:agent :object :required true :of :agent-spec :doc "the agent spec as data")
  (:task :string :required false :doc "initial user task")
  (:thread-id :string :required false :doc "owning thread id")
  (:max-turns :integer :required false :default 32 :doc "cap on tool-calls per run")
  (:timeout-ms :integer :required false :default 120000 :doc "per-invocation terminal wait")
  (:goal :object :required false :doc "stop condition as data (predicate spec)")
  (:soak :object :required false :of :soak-spec :doc "soak gate: {:n runs}"))

;; -- memo store/load ---------------------------------------------------------

(defn key-of
  "The content-hash key of manifest under memo. Pure: stores nothing."
  [memo manifest]
  (memo/key-of memo manifest))

(defn store!
  "Store manifest in memo under its content-hash key; return the key."
  [memo manifest]
  (memo/put! memo manifest))

(defn load-manifest
  "The manifest stored under key, or nil if absent."
  [memo key]
  (memo/get memo key))

;; -- validation + realization ------------------------------------------------

(defn validate
  "Decode candidate as the loop-manifest contract. Returns the validated
  manifest map, or a refusal map naming the first failing member."
  [candidate]
  (contracts/decode :loop-manifest candidate))

(defn manifest-agent
  "Build the agent step-fn the manifest's :agent spec describes. The spec is
  data; only its :kind decides how it is realized. :script -> scripted-agent."
  [manifest]
  (let [spec (:agent manifest)
        kind (some-> spec :kind name)]
    (case kind
      "script" (agent/scripted-agent (:script spec))
      (fail! (str "unknown agent kind: " (pr-str kind)) {:agent spec}))))

(defn run-manifest!
  "Drive one run of the loop the manifest describes over a faraday-rap server.
  opts: :rap (server handle, required) and :manifest, or :memo + :key to load
  it. :task/:thread-id/:max-turns/:timeout-ms/:goal override the manifest.
  Returns the run! outcome."
  [opts]
  (let [srv (:rap opts)
        m (or (:manifest opts)
              (load-manifest (:memo opts) (:key opts)))]
    (when-not srv (fail! "run-manifest! requires a :rap server" {:opts opts}))
    (when-not m (fail! "run-manifest! requires :manifest or (:memo + :key)" {:opts opts}))
    (let [base (select-keys m [:task :thread-id :max-turns :timeout-ms :goal])
          over (non-nil (select-keys opts [:task :thread-id :max-turns :timeout-ms :goal]))]
      (agent/run! (assoc (merge base over) :rap srv :agent (manifest-agent m))))))

;; -- the agent rewrites the loop as data --------------------------------------

(defn rewrite
  "Rewrite a manifest as data: apply rewrite-fn (a pure fn manifest -> data)
  and return the candidate. Does not validate or store — the gate does that.
  The agent rewrites the loop by returning data, never by calling code."
  [manifest rewrite-fn]
  (rewrite-fn manifest))

;; -- the soak + rollback gate -------------------------------------------------

(defn soak!
  "Run the candidate loop N times, each over a fresh rap server, and judge
  every run with harness. opts: :manifest, :server-fn (fn [] -> rap server,
  required), :n (default 5), :harness (fn outcome -> truthy; default :done).
  Returns {:passed? bool, :runs n, :outcomes [...]}."
  [opts]
  (let [manifest (:manifest opts)
        server-fn (:server-fn opts)
        n (or (:n opts) 5)
        harness (or (:harness opts) (fn [out] (= :done (:status out))))]
    (when-not server-fn (fail! "soak! requires a :server-fn" {:opts opts}))
    (when-not manifest (fail! "soak! requires a :manifest" {:opts opts}))
    (let [outcomes (mapv (fn [i]
                           (let [srv (server-fn)]
                             (run-manifest!
                              {:rap srv
                               :manifest manifest
                               :thread-id (str "soak_" (or (:thread-id manifest) "thr") "_" i)})))
                         (range n))]
      {:passed? (every? harness outcomes)
       :runs n
       :outcomes outcomes})))

(defn promote-loop!
  "The soak/rollback gate. Validate a candidate manifest against the declared
  loop-manifest contract, soak N runs against a harness over the pool (via
  :server-fn), and only then re-point the ledger at the new memo key. On any
  failure the ledger re-points back at the previous key (rollback).

  opts: :memo :ledger :server-fn (required), :manifest (candidate data,
  required), :pointer (name, default \"loop/active\"), :n, :harness.

  Returns {:status :promoted|:rolled-back|:rejected, :key, :previous-key,
           :pointer, :soak, ...}."
  [opts]
  (let [memo (:memo opts)
        ledg (:ledger opts)
        server-fn (:server-fn opts)
        pointer (or (:pointer opts) "loop/active")
        candidate (:manifest opts)]
    (when-not memo (fail! "promote-loop! requires a :memo" {:opts opts}))
    (when-not ledg (fail! "promote-loop! requires a :ledger" {:opts opts}))
    (when-not server-fn (fail! "promote-loop! requires a :server-fn" {:opts opts}))
    (when-not candidate (fail! "promote-loop! requires a :manifest" {:opts opts}))
    (let [valid (validate candidate)]
      (if (contracts/refusal? valid)
        {:status :rejected :reason :invalid :refusal valid :pointer pointer}
        (let [previous (ledger/pointer ledg pointer)
              soak (soak! {:manifest valid :server-fn server-fn
                           :n (:n opts) :harness (:harness opts)})]
          (if (:passed? soak)
            (let [key (memo/put! memo valid)]
              (ledger/put-pointer! ledg pointer key)
              {:status :promoted :key key :previous-key previous
               :pointer pointer :soak soak})
            (do (if previous
                  (ledger/put-pointer! ledg pointer previous)
                  (ledger/delete-pointer! ledg pointer))
                {:status :rolled-back :key nil :previous-key previous
                 :pointer pointer :soak soak})))))))
