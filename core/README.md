# faraday-core — the control-plane daemon

The plane daemon that ties the two security seams into one gate: a sandbox run
is authorized by the Cedar policy engine
([jolt-cedar](https://github.com/racklr-dev/jolt-cedar),
[faraday-contracts](../contracts/SPEC.md) §6) and only then admitted under a
resource budget ([jolt-admission](https://github.com/racklr-dev/jolt-admission),
§5). `gate` is that gate; `new-runner`/`run-sandbox!` wire it together with a
warm VM pool ([faraday-pool](../pool)) and a fanout runtime
([faraday-fanout](../fanout)) into the N-sandbox execution loop.

Two layers: `gate` (depends on jolt-admission + jolt-cedar only) and the
runner layer (adds faraday-pool + faraday-fanout).

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+
- A built Cedar native library — `JOLT_CEDAR_LIB`, or the release build next
  to the jolt-cedar checkout
- Real sandbox runs additionally need the faraday-pool requirements
  (libkrun + a writable `/dev/kvm`); the unit tests fake them

## Build

Nothing to compile. Run the suites (no KVM — microvm is stubbed, real
pool + ledger + fanout):

```sh
cd core && jolt run test/faraday/plane_test.clj
cd core && jolt run test/faraday/plane_wiring_test.clj
cd core && jolt run test/faraday/plane_e2e_test.clj
```

## Usage

```clojure
{:deps {racklr-dev/faraday-core
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "core"}}}
```

```clojure
(require '[faraday.core :as plane])

(def p (plane/new-plane
        {:budget {:cpu 4 :mem-mb 4096}
         :policy-set {:policies ["permit(principal, action, resource);"]
                      :schema {} :entities []}}))

(def r (plane/gate p {:request-id "req_1"
                      :principal {:type "Agent" :id "alice"}
                      :action    {:type "Action" :id "exec"}
                      :resource  {:type "Image" :id "oci://example@sha256:abc"}
                      :context {} :owner "thr_1" :cost {:cpu 1 :mem-mb 512}}))
;; => {:decision :allow :permit {:v 1 :status "granted" ...}}

(plane/release p (:permit r))
(plane/close! p)
```

Runner layer — one sandbox: gate → spawn → acquire → exec → release → report:

```clojure
(require '[faraday.core :as plane]
         '[faraday.pool :as pool]
         '[faraday.fanout :as fanout])

(def runner (plane/new-runner {:plane p :pool pl :fanout rt
                               :egress {:public {:allow-cidrs ["0.0.0.0/0"]}}}))

(plane/run-sandbox! runner
  {:request-id "req_1" :owner "thr_1"
   :image "oci://example@sha256:abc"
   :argv ["/bin/echo" "hello"]
   :egress {:ref :public}
   :cpu 1 :mem-mb 512 :timeout-ms 30000})
;; => {:status :ok :request-id "req_1" :stdout "hello\n" ...}

(plane/run-all! runner [req1 req2 req3])
(plane/close-runner! runner)
```

A request's `:egress` is an inline Egress Contract map or `{:ref id}` resolved
against the runner's `:egress` registry, then attached to the per-request
Sandbox entity (Cedar reads resource attributes from entities, not the
request).

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-plane` | `config` | plane handle | throws on a bad config or policy-validation error |
| `authorize` | `plane request` | keyword-keyed Cedar answer | throws on invalid policy |
| `decide` | `plane request` | `:allow` / `:deny` / `:error` | — |
| `admit` | `plane request` | permit | throws `:admission-denied` on deny/timeout |
| `release` | `plane permit` | the released permit | — |
| `gate` | `plane request` | `{:decision :allow :permit {...}}` | throws on deny, over-budget, or timeout |
| `stats` | `plane` | `{:budget ... :used ... :granted ... :waiting ...}` | — |
| `close!` | `plane` | nil | — |
| `new-runner` | `opts` | runner handle | throws on a missing `:plane`/`:pool`/`:fanout` |
| `run-sandbox!` | `runner request` | `{:status :ok\|:denied\|:error ...}` (never throws) | — |
| `run-all!` | `runner requests` | vector of one result per request | — |
| `close-runner!` | `runner` | nil | — |

`config` keys: `:budget` (resource → amount, required), `:weights`, `:policy-set`
(`:policies :schema :entities`), `:cedar-lib`. For an identity-scoped policy,
the Cedar source is `permit(principal == Agent::"alice", action, resource);` —
in Clojure, write it as
`(str "permit(principal == Agent::" (char 34) "alice" (char 34) ", action, resource);")`
or escape the quotes.

## Status

beta — budget + policy are wired end to end (the wiring test), and the RAP
transport drives concurrent tool invocations through the gate in the E2E test.
The CLI `serve` is a skeleton; the browser console ([faraday-console](../console))
is the real HTTP/SSE front end.

## License

EPL-2.0 (wrapper); cedar-policy is Apache-2.0.

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [jolt-cedar](https://github.com/racklr-dev/jolt-cedar) — the authorization seam
- [jolt-admission](https://github.com/racklr-dev/jolt-admission) — the budget seam
- [faraday-pool](../pool) — the warm VM pool the runner drives
