# jolt-fanout — task fanout runtime

One concrete mechanism: the task fanout runtime for the jolt control plane.
It emits Thread Messages (jolt-contracts SPEC.md §1) into a jolt-ledger, with
an optional cedar policy hook that can veto an emit before it is appended.

## Use

```clojure
;; deps.edn — published form (after jolt-ledger is pushed)
{:deps {racklr-dev/jolt-fanout
        {:git/url "https://github.com/racklr-dev/jolt-fanout"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.fanout :as fanout]
         '[jolt.ledger :as ledger])

(def rt (fanout/new-runtime {:ledger (ledger/new-ledger {:path "/var/lib/jolt/ledger"})}))
(fanout/spawn rt nil {:argv ["/bin/echo" "ok"]} nil)
(fanout/report rt "thr_1" "ok" {:exit-code 0} nil)
(fanout/close! rt)
```

## Status

Scaffold — the public API is pinned and tested, bodies throw `:not-implemented`.
The runtime implementation lands in the next card. Note: deps.edn currently
pins jolt-ledger via `:local/root` so the scaffold tests run before push; swap
it to a pinned `:git/sha` before publishing.

## License

EPL-2.0
