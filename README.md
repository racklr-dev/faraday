# faraday-ledger — durable idempotent thread state

One concrete mechanism: the single writer for Thread Messages in the jolt
control plane. It assigns each message a per-thread monotonic `seq`, dedups on
`msg-id`, and answers history / processed queries. Storage is delegated to
`jolt-lang/db`.

Standalone: nothing else in the control plane is required. It implements
[faraday-contracts](https://github.com/racklr-dev/faraday-contracts) SPEC.md §1
(Thread Message).

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/faraday-ledger
        {:git/url "https://github.com/racklr-dev/faraday-ledger"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[faraday.ledger :as ledger])

(def db (ledger/new-ledger {:path "/var/lib/jolt/ledger"}))
(ledger/append! db {:thread-id "t1" :msg-id "m1" :kind "spawn" :payload {}})
(ledger/history db "t1")
(ledger/processed? db "m1")
(ledger/close! db)
```

## Status

Implemented — durable SQLite storage (via `jolt-lang/db`), per-thread monotonic
`seq` assignment, `msg-id` dedup, and history/processed/last-seq queries. The
message body is stored as canonical wire JSON (contract keys in SPEC order, then
any extra keys sorted), read back as keyword-keyed maps. `append!` is atomic
(`BEGIN IMMEDIATE`): the dedup check and seq assignment are one transaction, so
a redelivery is an idempotent no-op that returns the original `{:seq n,
:deduped? true}`. The ledger is the single writer; one handle is one SQLite
connection.

Run the suite: `cd faraday-ledger && jolt run test/jolt/ledger_test.clj` — 47
checks: seq assignment, redelivery dedup, cross-thread independence, persistence
across close/reopen, the SPEC.md §1 canonical example, and validation errors.

Smoke gate (CI fast path):
`cd faraday-ledger && jolt run test/jolt/ledger_smoke.clj` — idempotent redelivery
across the close/reopen durable boundary.

## License

EPL-2.0
