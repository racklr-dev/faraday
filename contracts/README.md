# faraday-contracts

The canonical data contracts for the jolt control plane. This repo is a spec,
not a library: it holds `SPEC.md` (the pin) and `examples/` (canonical JSON test
vectors). Integrators implement the shapes in any language; every `jolt-*`
library tests against these fixtures so the boundaries stay compatible.

## Reading

- `SPEC.md` — the six contracts, field tables, invariants, change rules.
- `examples/` — one canonical JSON document per contract. These are the
test vectors.

## The contracts

| contract | MVP? | notes |
|---|---|---|
| Thread Message | yes | ledger + fanout |
| Sandbox Request | yes | vm-pool |
| Tool Invocation | yes | rap |
| Egress Contract | yes | vm-pool + tapmasq |
| Budget Permit | deferred | admission seam |
| Policy Set | deferred | cedar seam |

Wire encoding is JSON; `"v": 1` on every envelope; unknown keys ignored,
missing required keys rejected. See `SPEC.md` for the exact rules.
