;; faraday.harness.supervisor — the self-improvement gate.
;; The supervisor observes a task's loop-manifest run, proposes a rewrite as
;; data, and routes it through the same validate/soak/rollback gate the agent
;; loop uses (faraday.agent.loop/promote-loop!). The default proposer is a
;; mechanical budget-relaxer (raise :max-turns when a run exhausts it); the
;; :proposer seam is also where faraday.harness.model-proposer lands (CLI
;; `improve --proposer model`). Every proposal is recorded in supervisor_runs —
;; the supervisor's honesty ledger.
(ns faraday.harness.supervisor
  "Self-improvement: observe a loop-manifest run, propose a data rewrite, gate
  it (validate + soak + rollback), and persist the winner to the task. The
  proposer is a seam: deterministic budget-relaxer by default, or
  faraday.harness.model-proposer (a chat model) via `improve --proposer model`.
  Every attempt lands in supervisor_runs."
  (:require [faraday.harness :as h]
            [faraday.agent.loop :as loop]
            [faraday.contracts :as contracts]
            [faraday.ledger :as ldg]
            [faraday.memo :as memo]
            [faraday.fanout :as fanout]
            [faraday.rap :as rap]
            [clojure.java.io :as io]))

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.harness.supervisor/error true))))

(defn default-task-manifest
  "The task's loop-as-data when none is stored: a deterministic two-echo script.
  Shared by run-task! and the supervisor so both see the same default."
  [title]
  {:v 1
   :loop-id (str "task-" (h/sha-hex title))
   :name (str "task: " title)
   :agent {:kind "script"
           :script [{:tool-call {:name "run_shell" :args {:argv ["echo" (str "working on: " title)]} :display-as "echo"}}
                    {:done {:result :ok}}]}
   :max-turns 8})

(defn fake-run-hook
  "The no-KVM sandbox seam: every tool call succeeds with a fixed stdout."
  [_request]
  {:status :ok :exit 0 :stdout "sandbox: fake exec\n" :stderr ""})

(defn sandbox-server-fn
  "A (fn [] -> rap server) that serves tool calls on the fake sandbox, all
  backed by one fanout runtime over the given ledger. The soak + promote gate
  pulls a fresh server per run through this seam."
  [ledger]
  (let [rt (fanout/new-runtime {:ledger ledger})]
    (fn [] (rap/new-server {:run-hook fake-run-hook :fanout rt}))))

(defn budget-relaxer
  "The default proposer: (fn [manifest outcome] -> candidate-manifest|nil).
  Mechanical and safe — it only fixes the one failure it can prove: a run that
  exhausted its :max-turns budget. The candidate raises :max-turns by :delta
  (default max(4, 2x current)). Any other failure (error, unmet goal) returns
  nil: no mechanical rewrite is known, so the supervisor reports :no-proposal
  rather than guessing."
  [& {:keys [delta]}]
  (fn [manifest outcome]
    (when (and (= :max-turns (:status outcome))
               (number? (:max-turns manifest))
               (pos? (:max-turns manifest)))
      (let [cur (:max-turns manifest)
            d (or delta (max 4 (* 2 cur)))]
        (assoc manifest :max-turns (+ cur d))))))

(defn improve!
  "One self-improvement attempt for a task's loop manifest.

  opts: :root (project root), :task-id, :n (soak runs per side, default 3),
  :proposer ((fn [manifest outcome] -> candidate-manifest|nil), default
  budget-relaxer).

  Flow: read the task's current manifest -> soak the current manifest (the
  first soak outcome is the observation that motivates the proposal) -> propose
  a candidate -> route it through faraday.agent.loop/promote-loop! (validate +
  soak + ledger re-point) -> on promote, persist the winner to the task and
  record a supervisor_runs row either way.

  Returns {:status :improved|:no-improvement|:no-proposal|:rolled-back|:rejected
           :reason .. :task :proposal :gate ..}."
  [opts]
  (let [root (:root opts)
        task-id (:task-id opts)
        n (or (:n opts) 3)
        proposer (or (:proposer opts) (budget-relaxer))]
    (when-not root (fail! "improve! requires a :root" {:opts opts}))
    (when-not task-id (fail! "improve! requires a :task-id" {:opts opts}))
    (let [hd (h/new-harness {:path (str root "/.faraday/harness.db")})
          t (h/task hd task-id)]
      (when-not t (fail! (str "no such task " task-id) {:task-id task-id}))
      (let [current (or (:manifest t) (default-task-manifest (:title t)))
            valid (loop/validate current)]
        (if (contracts/refusal? valid)
          (do (h/record-supervisor-run! hd {:task-id task-id :verdict :rejected
                                            :observed-status :invalid})
              {:status :rejected :reason :invalid-current :refusal valid :task task-id})
          (let [run-root (str root "/.faraday/run/supervisor")
                _ (.mkdirs (io/file run-root))
                ledg (ldg/new-ledger {:path (str run-root "/ledger.db")})
                mem (memo/new-memo {:path (str run-root "/memo")})
                sfn (sandbox-server-fn ledg)
                baseline (loop/soak! {:manifest valid :server-fn sfn :n n})
                observed (first (:outcomes baseline))
                candidate (proposer valid observed)
                candidate-valid (when candidate (loop/validate candidate))
                proposal (when candidate {:max-turns (:max-turns candidate)
                                          :from (:max-turns valid)})
                gate (when (and candidate (not (contracts/refusal? candidate-valid)))
                       (loop/promote-loop! {:memo mem :ledger ledg :server-fn sfn
                                            :manifest candidate-valid
                                            :pointer "loop/active" :n n}))
                verdict (cond
                          (nil? candidate)
                          {:status (if (:passed? baseline) :no-improvement :no-proposal)
                           :reason (if (:passed? baseline)
                                     :baseline-green-no-proposal
                                     :no-mechanical-rewrite)}

                          (contracts/refusal? candidate-valid)
                          {:status :rejected :reason :invalid-candidate :refusal candidate-valid}

                          (= :promoted (:status gate))
                          {:status :improved :gate gate}

                          (= :rolled-back (:status gate))
                          {:status :rolled-back :reason :soak-failed :gate gate}

                          :else
                          {:status :rejected :reason :gate-rejected :gate gate})]
            (when (= :improved (:status verdict))
              (h/task-set-manifest! hd task-id candidate-valid))
            (h/record-supervisor-run!
             hd {:task-id task-id
                 :observed-status (str (:status observed))
                 :baseline-passed (:passed? baseline)
                 :proposal proposal
                 :candidate-key (when candidate (loop/key-of mem candidate-valid))
                 :verdict (:status verdict)
                 :soak-runs (get-in gate [:soak :runs])
                 :pointer "loop/active"
                 :promoted-key (when (= :improved (:status verdict)) (:key gate))})
            (merge {:task task-id :proposal proposal} verdict)))))))
