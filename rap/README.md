# faraday-rap — RAP tool server + MCP adapter

The RAP tool server and MCP adapter for the jolt control plane: it runs Tool
Invocations ([faraday-contracts](../contracts/SPEC.md) §3) against a
[faraday-pool](../pool), streams results to subscribers, and adapts them to
MCP. Each invocation has exactly one terminal result, keyed by `call-id`.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+
- [faraday-pool](../pool) + [faraday-fanout](../fanout) — monorepo `:local/root`
  dependencies

## Build

Nothing to compile. Run the suites:

```sh
# unit: fakes vm-pool/microvm, real ledger/fanout
cd rap && jolt run test/faraday/rap_test.clj

# integration: real KVM microVM through a real vm-pool
cd rap && jolt run test/faraday/rap_integration.clj
```

## Usage

```clojure
{:deps {racklr-dev/faraday-rap
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "rap"}}}
```

```clojure
(require '[faraday.rap :as rap])

(def srv (rap/new-server {:vm-pool pool :fanout rt}))
(rap/subscribe srv call-id (fn [frame] (println frame)))
(rap/invoke srv {:v 1 :thread-id "thr_1" :name "run_shell" :args {:argv ["ls"]}})
(rap/close! srv)
```

`new-server` takes either `:vm-pool` (the server owns acquire/exec/release) or
`:run-hook` (the caller owns the side effects — faraday-core uses this to run
invocations through the gate):

```clojure
(def srv (rap/new-server {:fanout rt
                          :run-hook (fn [request] (plane/run-sandbox! runner request))}))
```

With `:run-hook` the server no longer acquires/releases the pool and no longer
emits the report Thread Message — the hook owns those; the server maps the
hook's result to the RAP terminal frame (`:denied` → error code `"denied"`,
`:error` → the error code, `:ok` + exit 0 → `"ok"`).

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-server` | `opts` | server handle | throws on a missing `:vm-pool`/`:run-hook` or `:fanout` |
| `invoke` | `srv call` | `call-id` | throws on a bad call |
| `subscribe` | `srv call-id cb` | subscription | — |
| `on-result` | `srv call-id cb` | subscription | — |
| `cancel` | `srv call-id` | nil | — |
| `close` | `srv id` | nil | — |
| `close!` | `srv` | nil | — |

`subscribe`/`on-result` work before or after `invoke` (pending subscriptions
attach on invoke; late ones replay the stored terminal). `cancel`/`close`
short-circuit in-flight calls with a terminal cancelled frame.

## Status

beta — the unit test fakes only vm-pool/microvm and runs against a real
ledger/fanout; the integration test drives a real KVM microVM through a real
vm-pool and asserts the async terminal result lands via `on-result`.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-pool](../pool) — the sandbox source
- [faraday-fanout](../fanout) — the thread-message sink
- [faraday-contracts](../contracts/SPEC.md) — SPEC.md §3, the Tool Invocation shape
