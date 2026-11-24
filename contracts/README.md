# faraday-contracts — the narrow waist

The canonical data contracts for a jolt-based control plane. `SPEC.md` is the
single pin for every data shape that crosses a component boundary; the
`faraday.contracts` namespace is its executable form: each shape is declared
once with `define-contract`, and the decoder, encoder and JSON-Schema
projection are derived from that declaration, never restated.

Wire encoding is JSON; `"v": 1` on every envelope; unknown keys are ignored,
missing required keys are rejected; timestamps are RFC 3339 UTC; ids are
opaque strings. `examples/` holds the canonical test vectors every library
consumes.

## The library

```clojure
(require '[faraday.contracts :as c])

(c/decode  :tool-invocation obj) ; JSON object or keyword map -> validated map
(c/encode  :tool-invocation rec) ; validated map -> string-keyed wire object
(c/decode! :sandbox-request obj) ; throw on refusal
(c/schema  :sandbox-request)     ; JSON-Schema projection
(c/valid?  :egress-contract obj) ; boolean
```

`decode` returns a refusal map (never throws) naming the first member that
failed; `decode!`/`encode!` throw. decode reads both string-keyed JSON and
in-process keyword maps, so a producer in this image builds the payload the
consumer decodes through the same declaration and the two ends of one boundary
cannot drift.

## Contracts

| contract | produced by | consumed by | MVP? |
|---|---|---|---|
| 1. Thread Message | faraday-fanout | faraday-ledger, faraday-fanout, faraday-rap | yes |
| 2. Sandbox Request | faraday-fanout | faraday-pool | yes |
| 3. Tool Invocation | faraday-rap | faraday-fanout, faraday-rap | yes |
| 4. Egress Contract | operator/config | faraday-pool, jolt-tapmasq | yes |
| 5. Budget Permit | jolt-admission | faraday-pool, faraday-fanout | deferred |
| 6. Policy Set | operator/config | jolt-cedar | deferred |

Deferred contracts are pinned now so the MVP libraries can accept them as
hooks later; nothing in the MVP path is blocked on them.

## Run

```
cd contracts && jolt run test/faraday/contracts_test.clj
```

## License

Eclipse Public License - v 2.0
