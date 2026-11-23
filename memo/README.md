# faraday-memo — content-addressed result cache

A content-hash-keyed cache for the jolt control plane: it maps the canonical
content hash of a value to that value, so identical inputs share one stored
copy. faraday-fanout uses it behind a Thread Message's optional `memo-key`
([faraday-contracts](../contracts/SPEC.md) §1) to skip recomputing a spawn
result.

Standalone: no control-plane dependency and no runtime deps
(`{:paths ["src"]}`). Keys are opaque hex strings.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ to run the tests
- No other libraries — the SHA-256 is vendored in private namespaces

## Build

Nothing to compile. Run the suite:

```sh
cd memo && jolt run test/faraday/memo_test.clj    # 26 checks
cd memo && jolt run test/faraday/memo_smoke.clj   # CI fast path
```

## Usage

```clojure
{:deps {racklr-dev/faraday-memo
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "memo"}}}
```

```clojure
(require '[faraday.memo :as memo])

(def cache (memo/new-memo {:path "/var/lib/jolt/memo"}))
(def k (memo/put! cache {:status "ok" :result 42}))  ; => hex key
(memo/contains? cache k)  ; => true
(memo/get cache k)        ; => {:status "ok" :result 42}
(memo/delete! cache k)
(memo/stats cache)
(memo/close! cache)
```

`put!` returns the canonical key (map and set order insensitive, vector order
sensitive); re-putting the same value is an idempotent no-op that returns the
same key.

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-memo` | `opts` | cache handle | throws on a bad `:path` |
| `key-of` | `value` | the canonical hex key | — |
| `put!` | `memo value` | the canonical hex key | — |
| `get` | `memo key` | the stored value, or nil | — |
| `contains?` | `memo key` | boolean | — |
| `delete!` | `memo key` | nil | — |
| `stats` | `memo` | `{:entries n ...}` | — |
| `close!` | `memo` | nil | — |

## Status

beta — 26 checks cover the FIPS 180-4 SHA-256 KATs, canonical keying, and
put!/get/contains?/delete!/stats round-trips including idempotent re-put.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-fanout](../fanout) — the consumer of `memo-key`
- [faraday-contracts](../contracts/SPEC.md) — SPEC.md §1, the Thread Message shape
