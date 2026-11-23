# faraday — one control plane, one gate, one console

Faraday is the racklr control plane: it admits work against a resource budget,
authorizes it with [Cedar](https://github.com/cedar-policy/cedar) policy, runs
it inside real KVM microVMs ([libkrun](https://github.com/containers/libkrun)
2.0), records every thread message in an append-only ledger, and shows the
whole plane live in a browser.

This repository is a monorepo for the plane's libraries and the console. The
jolt-* substrate it builds on (jolt-admission, jolt-cedar, jolt-microvm,
jolt-oci, jolt-libkrun, jolt-pty, jolt-exec, jolt-tapmasq) stays in its own
pinned git dependencies.

## Modules

| dir | library | role |
|---|---|---|
| `contracts/` | faraday-contracts | the wire spec: messages, requests, invocations, permits, policies, egress |
| `core/` | faraday.core | the plane: budget admission + Cedar authorization + sandbox runs |
| `pool/` | faraday.pool | warm VM pool over jolt-microvm + jolt-oci |
| `rap/` | faraday.rap | tool invocation server (requests → runs) |
| `fanout/` | faraday.fanout | thread-message fanout over the ledger |
| `ledger/` | faraday.ledger | single writer, append-only thread ledger |
| `memo/` | faraday.memo | content-addressed message memoization |
| `console/` | faraday.console | the web console (glimmer-datastar + SSE) |

Internal modules reference each other via `:local/root`; each module keeps its
own README with its API and status.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ (the jolt-* substrate
  declares `:jolt/min-version 0.8.28`)
- To run real sandboxes: libkrun + libkrunfw, a writable `/dev/kvm`, and
  `ssh`/`ssh-keygen` on the host — see [pool](pool) and
  [jolt-microvm](https://github.com/racklr-dev/jolt-microvm)
- To open a plane: a built Cedar native library — see [core](core) and
  [jolt-cedar](https://github.com/racklr-dev/jolt-cedar)

## Usage

```sh
jolt -M:serve   # from the repo root; then http://127.0.0.1:8787
```

The console composes the whole plane in-process: the dashboard is a peer of
the gate, not a client, so the budget/policy gate stays authoritative.

## Status

beta — the MVP plane runs end-to-end in-process: real KVM sandboxes, Cedar
authorization, budget admission, ledger/fanout, and the live console. See each
module's README for what its own tests cover.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-contracts](contracts/SPEC.md) — the wire spec every module implements
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the sandbox substrate
- [libkrun](https://github.com/containers/libkrun) — the microVM runtime
