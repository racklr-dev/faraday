# faraday-fanout — task fanout runtime

One concrete mechanism: the task fanout runtime for the jolt control plane.
It emits Thread Messages (jolt-contracts SPEC.md §1) into a faraday-ledger, with
an optional cedar policy hook that can veto an emit before it is appended.

## Use

```clojure
;; deps.edn — published form (after faraday-ledger is pushed)
{:deps {racklr-dev/faraday-fanout
        {:git/url "https://github.com/racklr-dev/faraday-fanout"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[faraday.fanout :as fanout]
         '[faraday.ledger :as ledger])

(def rt (fanout/new-runtime {:ledger (ledger/new-ledger {:path "/var/lib/jolt/ledger"})}))
(fanout/spawn rt nil {:argv ["/bin/echo" "ok"]} nil)
(fanout/report rt "thr_1" "ok" {:exit-code 0} nil)
(fanout/close! rt)
```

## Status

Implemented. The runtime shapes Thread Messages (SPEC.md §1) and appends them
via faraday-ledger: `spawn`/`inherit` mint a new thread (fresh `thread-id`, `parent-id`
links the tree), `report`/`yield` append into an existing `thread-id`, and `emit`
appends any already-shaped message. A `:policy-hook` (if given) vetoes an emit
before it reaches the ledger (`:policy-vetoed`). The test runs against a real
ledger (no mocks) — `test/jolt/fanout_test.clj`.

Integration gate:
`cd faraday-fanout && jolt run test/jolt/fanout_integration_test.clj` — builds a
fanout tree (spawn + inherit), closes and reopens the ledger, then reconstructs
the parent→children edges and verifies the tree and payloads round-tripped.

deps.edn pins faraday-ledger to its published `:git/sha` on
`https://github.com/racklr-dev/faraday-ledger`.

## License

EPL-2.0
