# jolt-ledger — durable idempotent thread state

One concrete mechanism: the single writer for Thread Messages in the jolt
control plane. It assigns each message a per-thread monotonic `seq`, dedups on
`msg-id`, and answers history / processed queries. Storage is delegated to
`jolt-lang/db`.

Standalone: nothing else in the control plane is required. It implements
[jolt-contracts](https://github.com/racklr-dev/jolt-contracts) SPEC.md §1
(Thread Message).

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-ledger
        {:git/url "https://github.com/racklr-dev/jolt-ledger"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.ledger :as ledger])

(def db (ledger/new-ledger {:path "/var/lib/jolt/ledger"}))
(ledger/append! db {:thread-id "t1" :msg-id "m1" :kind "spawn" :payload {}})
(ledger/history db "t1")
(ledger/processed? db "m1")
(ledger/close! db)
```

## Status

Scaffold — the public API is pinned and tested, bodies throw `:not-implemented`.
The durable implementation lands in the next card.

## License

EPL-2.0
