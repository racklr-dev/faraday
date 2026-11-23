# faraday-contracts — canonical data contracts for the jolt control plane

The canonical wire spec for the jolt control plane: it holds `SPEC.md` (the
single pin for every data shape that crosses a component boundary) and
`examples/` (canonical JSON test vectors). Integrators implement the shapes in
any language; every faraday and jolt-* library tests against these fixtures so
the boundaries stay compatible.

This is a spec, not a library — there is no code to build or import.

## Requirements

- None. The spec is plain markdown and JSON; read it with any editor.

## Usage

Read `SPEC.md` for the field tables, invariants, and change rules; consume
`examples/` as canonical test vectors.

## The contracts

| contract | produced by | consumed by | MVP? |
|---|---|---|---|
| 1. Thread Message | faraday-fanout | faraday-ledger, faraday-fanout, faraday-rap | yes |
| 2. Sandbox Request | faraday-fanout | faraday-pool | yes |
| 3. Tool Invocation | faraday-rap | faraday-fanout, faraday-rap | yes |
| 4. Egress Contract | operator/config | faraday-pool, jolt-tapmasq | yes |
| 5. Budget Permit | jolt-admission | faraday-pool, faraday-fanout | deferred |
| 6. Policy Set | operator/config | jolt-cedar | deferred |

Wire encoding is JSON; `"v": 1` on every envelope; unknown keys are ignored,
missing required keys are rejected. Deferred contracts are pinned now and
implemented by jolt-admission / jolt-cedar, but not on the MVP critical path.

## Status

stable — the spec is version 1.0 and the canonical examples are the test
vectors every library consumes.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-core](../core) — the plane that implements the gate over contracts 5 and 6
- [faraday-rap](../rap) — the tool-invocation server (contract 3)
