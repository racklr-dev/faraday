# faraday-memo — content-addressed result cache

One concrete mechanism: a content-hash-keyed cache for the jolt control
plane. It maps the canonical content hash of a value to that value, so
identical inputs share one stored copy. jolt-fanout uses it behind a Thread
Message's optional `memo-key` (jolt-contracts SPEC.md §1) to skip recomputing
a spawn result.

Standalone: no control-plane dependency. Keys are opaque hex strings.

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/faraday-memo
        {:git/url "https://github.com/racklr-dev/faraday-memo"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[faraday.memo :as memo])

(def cache (memo/new-memo {:path "/var/lib/jolt/memo"}))
(def k (memo/put! cache {:status "ok" :result 42}))
(memo/contains? cache k)
(memo/get cache k)
(memo/delete! cache k)
(memo/close! cache)
```

## Status

Implemented — content-addressed cache over a pure-Clojure SHA-256 (vendored
from racklr-dev/jolt-merkle @ 06e135f). A 26-check behavior suite is green
(`jolt run test/jolt/memo_test.clj`): FIPS 180-4 KATs, canonical keying (map
and set order insensitive, vector order sensitive), put!/get/contains?/
delete!/stats round-trips, and idempotent re-put.

No runtime deps (`{:paths ["src"]}`). The SHA-256 lives in the private
namespaces `faraday.memo.sha256` and `faraday.memo.bit`, so a memo keeps a
zero-dependency graph.

## License

EPL-2.0
