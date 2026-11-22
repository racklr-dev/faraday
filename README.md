# jolt-memo — content-addressed result cache

One concrete mechanism: a content-hash-keyed cache for the jolt control
plane. It maps the canonical content hash of a value to that value, so
identical inputs share one stored copy. jolt-fanout uses it behind a Thread
Message's optional `memo-key` (jolt-contracts SPEC.md §1) to skip recomputing
a spawn result.

Standalone: no control-plane dependency. Keys are opaque hex strings.

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-memo
        {:git/url "https://github.com/racklr-dev/jolt-memo"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.memo :as memo])

(def cache (memo/new-memo {:path "/var/lib/jolt/memo"}))
(def k (memo/put! cache {:status "ok" :result 42}))
(memo/contains? cache k)
(memo/get cache k)
(memo/delete! cache k)
(memo/close! cache)
```

## Status

Scaffold — the public API is pinned and tested, bodies throw `:not-implemented`.
The content-hash implementation lands in the next card.

## License

EPL-2.0
