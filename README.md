# faraday

One control plane, one gate, one console.

Faraday is the racklr control plane: it admits work against a budget, authorizes it
with Cedar policy, runs it inside real KVM microvms (libkrun 2.0), records every
thread message in an append-only ledger, and shows the whole plane live in a browser.

## Modules

| dir          | lib                | role                                                         |
|--------------|--------------------|--------------------------------------------------------------|
| contracts/   | (SPEC.md)          | the contract: messages, requests, invocations, permits, policies, egress |
| core/        | faraday.core       | the plane: budget admission + Cedar authorization + sandbox runs |
| pool/        | faraday.pool       | VM pool: warm microvm lifecycle (jolt-microvm + jolt-oci)     |
| rap/         | faraday.rap        | tool invocation server (requests -> runs)                     |
| fanout/      | faraday.fanout     | thread-message fanout over the ledger                         |
| ledger/      | faraday.ledger     | single writer, append-only thread ledger                      |
| memo/        | faraday.memo       | message memoization (optional memo-key)                       |
| console/     | faraday.console    | the web console (glimmer-datastar + SSE)                      |

Internal modules reference each other via `:local/root`; the jolt-* substrate
(jolt-admission, jolt-cedar, jolt-microvm, jolt-oci, jolt-libkrun, jolt-pty,
jolt-exec, jolt-tapmasq, …) stays as pinned git deps.

## Run

```
jolt -M:serve          # from the repo root, or: cd console && jolt -M:serve
```

Requires jolt 0.8.28+ (the racklr-dev jolt-* libraries declare
`:jolt/min-version 0.8.28`).
