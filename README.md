# faraday — one control plane, one gate, one console

Faraday is an agent harness with a control plane: it admits work against a resource budget,
authorizes it with [Cedar](https://github.com/cedar-policy/cedar) policy, runs
it inside real KVM microVMs ([libkrun](https://github.com/containers/libkrun)
2.0), records every thread message in an append-only ledger, and shows the
whole plane live in a browser, a tui, or in a shared ledger.

This repository is a monorepo composed of various jolt libraries. The
jolt-* it builds on (jolt-cedar, jolt-microvm,
jolt-oci, jolt-libkrun, jolt-pty, jolt-exec, jolt-tapmasq, as well as glimmer-tui, glimmer-tui-client) are in their own
pinned git dependencies.

## Modules

| dir | library | role |
|---|---|---|
| `contracts/` | faraday-contracts | the declared wire contracts: one definition per shape, derived decode/encode/schema |
| `core/` | faraday.core | the plane: budget admission + Cedar authorization + sandbox runs |
| `pool/` | faraday.pool | warm VM pool over jolt-microvm + jolt-oci |
| `thread/` | faraday.thread | thread-keyed sandbox + repo store (hibernate/restore): a ThreadId keys an agent's sandbox and repo state |
| `rap/` | faraday.rap | tool invocation server (requests → runs) |
| `agent/` | faraday.agent | the agent framework: loop-as-data, model step-fn, the launch/steer/observe harness, plus a yieldable loop (faraday.agent.yield) and a bounded scheduler (faraday.agent.scheduler) |
| `fanout/` | faraday.fanout | thread-message fanout over the ledger |
| `ledger/` | faraday.ledger | single writer, append-only thread ledger |
| `memo/` | faraday.memo | content-addressed message memoization |
| `memory/` | faraday.memory | two-tier agent memory + FTS5 over ledger/memo + checkpoint/resume |
| `harness/` | faraday.harness | the harness DB (projects/scopes/hooks/hook_runs/tasks) + the runnable CLI |
| `console/` | faraday.console | the web console (glimmer-datastar + SSE) |

Internal modules reference each other via `:local/root`; each module keeps its
own README with its API and status.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+ (the jolt-* 
  declares `:jolt/min-version 0.8.28`)
- To run real sandboxes: libkrun + libkrunfw, a writable `/dev/kvm`, and
  `ssh`/`ssh-keygen` on the host — see [pool](pool) and
  [jolt-microvm](https://github.com/racklr-dev/jolt-microvm)
- To open a plane: a built Cedar native library — see [core](core) and
  [jolt-cedar](https://github.com/racklr-dev/jolt-cedar)

## Usage

```sh
jolt -M:serve   # from the repo root; then http://127.0.0.1:8787
jolt -M:harness -- help   # the harness CLI: board, scopes, hooks, tasks, honest runs, improve
```

The console composes the whole plane in-process: the dashboard is a peer of
the gate, not a client, so the budget/policy gate stays authoritative.

## Status

beta — the MVP plane runs end-to-end in-process: real KVM sandboxes, Cedar
authorization, budget admission, ledger/fanout, the live console, and the
agent stack (loop-as-data manifests with a soak/rollback gate, model
step-fn, two-tier memory, and the launch/steer/observe harness), plus the
async agent runtime: faraday.thread keys each ThreadId to a sandbox + repo
worktree, faraday.agent.yield parks a waiting agent as checkpointed data (no
live process between turns), and faraday.agent.scheduler resumes it from the
rap result callback over a bounded warm VM pool — infinity's model. Also the
first self-improvement slice: `faraday improve` observes a loop run, proposes
a manifest rewrite, and routes it through the same soak/rollback gate, then
persists the winner to the task. The middleware spine (faraday.agent.mw) now
compiles a manifest + deps into the ROADMAP wrap-* chain — history windowing,
hot-memory injection, the routed/repairing model step, one permission decision
point, the finalization goal judge, and per-cell guardrails that fire a scope's
pre/post hooks into hook_runs — and `faraday run` drives every task through it.
See each module's README for what its own tests cover.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [faraday-contracts](contracts/SPEC.md) — the wire spec every module implements
- [docs/settings.md](docs/settings.md) — the settings.json reference, including `sandbox.nix_flake` / `.#default`
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the sandbox 
- [libkrun](https://github.com/containers/libkrun) — the microVM runtime
