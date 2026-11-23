# faraday-ledger — durable idempotent thread state

The single writer for Thread Messages in the jolt control plane: it assigns
each message a per-thread monotonic `seq`, dedups on `msg-id`, and answers
history / processed / last-seq queries. Storage is delegated to
[jolt-lang/db](https://github.com/jolt-lang/db) (SQLite).

Standalone: nothing else in the control plane is required. It implements
[faraday-contracts](../contracts/SPEC.md) §1 (Thread Message).

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests
- [jolt-lang/db](https://github.com/jolt-lang/db) — a pinned git dependency,
  resolved automatically by `deps.edn`

## Build

Nothing to compile. Run the suite:

```sh
cd ledger && jolt run test/faraday/ledger_test.clj    # 47 checks
cd ledger && jolt run test/faraday/ledger_smoke.clj   # CI fast path
```

## Usage

This module lives in the faraday monorepo. Depend on it via a `:deps/root`
subdirectory coordinate (or `:local/root` from inside the monorepo):

```clojure
{:deps {racklr-dev/faraday-ledger
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "ledger"}}}
```

```clojure
(require '[faraday.ledger :as ledger])

(def db (ledger/new-ledger {:path "/var/lib/jolt/ledger"}))
(ledger/append! db {:thread-id "t1" :msg-id "m1" :kind "spawn" :payload {}})
;; => {:seq 0}
(ledger/history db "t1")   ; => [{:thread-id "t1" :msg-id "m1" :seq 0 ...}]
(ledger/processed? db "m1") ; => true
(ledger/close! db)
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-ledger` | `opts` | ledger handle | throws on a bad `:path` |
| `append!` | `ledger msg` | `{:seq n}`, or `{:seq n :deduped? true}` on a redelivery | throws on an invalid message |
| `history` | `ledger thread-id` | vector of keyword-keyed messages (with `:seq`) | — |
| `processed?` | `ledger msg-id` | boolean | — |
| `last-seq` | `ledger thread-id` | the thread's last assigned `:seq` | — |
| `close!` | `ledger` | nil | — |

`append!` is atomic (`BEGIN IMMEDIATE`): the dedup check and seq assignment are
one transaction. One handle is one SQLite connection — the ledger is the single
writer. The message body is stored as canonical wire JSON and read back
keyword-keyed.

## Status

beta — 47 checks cover seq assignment, redelivery dedup, cross-thread
independence, persistence across close/reopen, the SPEC §1 canonical example,
and validation errors. The smoke test is the CI fast path.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [jolt-lang/db](https://github.com/jolt-lang/db) — the SQLite storage layer
- [faraday-contracts](../contracts/SPEC.md) — SPEC.md §1, the Thread Message shape
- [faraday-fanout](../fanout) — the writer that emits into this ledger
