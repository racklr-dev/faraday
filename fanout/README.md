# faraday-fanout — task fanout runtime

The task fanout runtime for a jolt-based control plane: it emits Thread Messages
([faraday-contracts](../contracts/SPEC.md) §1) into a
[faraday-ledger](../ledger), with an optional Cedar policy hook that can veto
an emit before it is appended.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests
- [faraday-ledger](../ledger) — a `:local/root` dependency in the monorepo

## Build

Nothing to compile. Run the suites:

```sh
cd fanout && jolt run test/faraday/fanout_test.clj
cd fanout && jolt run test/faraday/fanout_integration_test.clj
```

## Usage

```clojure
{:deps {racklr-dev/faraday-fanout
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "fanout"}}}
```

```clojure
(require '[faraday.fanout :as fanout]
         '[faraday.ledger :as ledger])

(def rt (fanout/new-runtime {:ledger (ledger/new-ledger {:path "/var/lib/jolt/ledger"})}))
(fanout/spawn rt nil {:argv ["/bin/echo" "ok"]} nil)
(fanout/report rt "thr_1" "ok" {:exit-code 0} nil)
(fanout/close! rt)
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-runtime` | `opts` | runtime handle | throws on a missing `:ledger` |
| `emit` | `rt msg` | the appended message | throws `:policy-vetoed` from a `:policy-hook` |
| `spawn` | `rt parent-id spec memo-key` | the spawn Thread Message | throws `:policy-vetoed` |
| `inherit` | `rt from-thread-id snapshot-ref` | the child Thread Message | throws `:policy-vetoed` |
| `report` | `rt thread-id status result segments` | the report Thread Message | throws `:policy-vetoed` |
| `yield` | `rt thread-id state-hash snapshot-ref` | the yield Thread Message | throws `:policy-vetoed` |
| `close!` | `rt` | nil | — |

`spawn`/`inherit` mint a new thread (fresh `thread-id`, `parent-id` links the
tree); `report`/`yield` append into an existing `thread-id`; `emit` appends an
already-shaped message.

## Status

beta — the unit test runs against a real ledger (no mocks); the integration
test builds a spawn/inherit tree, closes and reopens the ledger, then
reconstructs the parent→children edges and verifies the tree and payloads
round-tripped.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-contracts](../contracts/SPEC.md) — SPEC.md §1, the Thread Message shape
- [faraday-ledger](../ledger) — the append-only store this writes into
