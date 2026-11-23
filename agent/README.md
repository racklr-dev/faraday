# faraday.agent — the agent framework

One concrete mechanism: the loop that drives an agent across sandbox execs. An
agent proposes tool calls; the framework routes each one through a faraday-rap
tool server (Tool Invocation, SPEC sec 3), waits for the terminal frame, and
feeds the result back for the next step — until the agent finishes, the turn
budget is spent, or a step errors.

The agent itself is a plain step function; faraday.agent owns the loop, the
thread, and the transcript. Any agent — a scripted sequence, a model-backed
planner, or dsh-superagent's subprocess backend shimmed onto rap — plugs in as
`(fn [ctx] action)`.

## Use

```clojure
(require '[faraday.agent :as agent] '[faraday.rap :as rap])

(def srv (rap/new-server {:vm-pool pool :fanout rt}))

(def my-agent
  (fn [ctx]
    ;; read ctx ( :task :transcript :turns ) and decide the next action
    (if (< (:turns ctx) 3)
      {:tool-call {:name "run_shell"
                   :args {:argv ["echo" (str "step " (:turns ctx))]}
                   :display-as "echo"}}
      {:done :finished}})))

(agent/run! {:rap srv :agent my-agent :task "do three steps" :max-turns 8})
;; => {:status :done :result :finished :turns 3
;;     :transcript [ {:role :user ...}
;;                   {:role :tool-call ...} {:role :tool-result :status "ok" ...}
;;                   ... {:role :assistant :content :finished} ]
;;     :frames [...]}
```

`scripted-agent` replays a fixed list of actions and is the test/demo agent.
`run-all!` fans N independent agent tasks out concurrently over one rap server.

## Namespaces

- `faraday.agent` — the loop (`run!`, `:goal` stop-condition, `run-all!`).
- `faraday.agent.loop` — loop-as-data: the turn loop is an EDN manifest in memo,
  validated by the plane and promoted through a soak/rollback gate.
- `faraday.agent.model` — a robust model step-fn (repair→validate→escalate→
  circuit-break) over a provider alias/role config.
- `faraday.agent.harness` — the operator surface: launch/steer/observe N agents.

Each namespace has a test under `test/faraday/` (`agent_test`, `loop_test`,
`model_test`, `harness_test`); run with `jolt run test/faraday/<name>.clj` from
this module.

## Status

Implemented. `run!` drives a scripted agent through three sandbox execs and
asserts the transcript, the shell-quoted argv, the streamed terminal frames, the
ledger report messages, the turn budget, error recovery after a rejected invoke,
and concurrent `run-all!` fanout — all against a real ledger/fanout with a faked
vm-pool/microvm (no KVM).

## Running the tests

    cd agent && jolt run test/faraday/agent_test.clj
