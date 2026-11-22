# jolt-rap — RAP tool server + MCP adapter

One concrete mechanism: the RAP tool server and MCP adapter for the jolt
control plane. It runs Tool Invocations (jolt-contracts SPEC.md §3) against a
jolt-vm-pool, streams results to subscribers, and adapts them to MCP. Each
invocation has exactly one terminal result, keyed by `call-id`.

## Use

```clojure
;; deps.edn — published form (after fanout/vm-pool are pushed)
{:deps {racklr-dev/jolt-rap
        {:git/url "https://github.com/racklr-dev/jolt-rap"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.rap :as rap])

(def srv (rap/new-server {:vm-pool pool :fanout rt}))
(rap/subscribe srv call-id (fn [frame] (println frame)))
(rap/invoke srv {:v 1 :thread-id "thr_1" :name "run_shell" :args {:argv ["ls"]}})
(rap/close! srv)
```

## Status

Implemented. The server runs Tool Invocations (SPEC.md §3) asynchronously
against the vm-pool: `invoke` builds a Sandbox Request from the call args,
acquires a sandbox, runs the shell-quoted argv, releases the sandbox, emits a
report Thread Message via fanout, and delivers exactly one terminal result.
`subscribe`/`on-result` work before or after `invoke` (pending subscriptions
attach on invoke; late ones replay the stored terminal). `cancel`/`close`
short-circuit in-flight calls with a terminal cancelled frame; `close!` releases
a subscription or stops the server. The test fakes only vm-pool/microvm and runs
against a real ledger/fanout — `test/jolt/rap_test.clj`.

deps.edn pins jolt-fanout and jolt-vm-pool to their published `:git/sha` values
on `https://github.com/racklr-dev`.

## License

EPL-2.0
