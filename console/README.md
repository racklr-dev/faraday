# faraday-console — the live control-plane dashboard

Browser control-plane dashboard for the faraday plane, built natively on jolt.
It serves a live SSE board (plane stats, gate decisions, runs, calls, threads,
sandboxes) with reactive, id-part-diffed Datastar views.

- [glimmer-datastar](https://github.com/jolt-lang/glimmer-datastar) — the
  Datastar v1.0 server (reactive render loop, tick batching, frame dropping).
- [ring-chez-adapter](https://github.com/jolt-lang/ring-chez-adapter) — HTTP
  over BSD sockets (`:strategy :fibers`), with gzip that also compresses the
  SSE stream.
- [faraday-core](../core) — composed in-process: the dashboard is a peer of
  the plane, not a client, so the budget/policy gate stays authoritative.

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+
- A built Cedar native library (`JOLT_CEDAR_LIB`) to open a plane
- The faraday-pool requirements (libkrun + a writable `/dev/kvm`) for real
  sandbox runs

## Build

Nothing to compile. Run it:

```sh
jolt -M:serve     # from the repo root; then http://127.0.0.1:8787
```

## Usage

The default config opens an identity-scoped plane (alice/bob permitted, others
denied by default; an egress forbid drops anything touching 8.8.8.8/32) and
drives a rotating demo through the real RAP run-hook, so every view has live
data. The seven views (Overview, Runs, Agents, Threads, VMs, Policy, Egress)
switch client-side on a `$view` signal.

## API

| Entry point | Args | Role |
|---|---|---|
| `handler` | `req` | the ring handler (Datastar SSE + HTML) |
| `open-runtime!` | `opts` | compose plane + pool + rap + ledger/fanout in-process |
| `start-demo!` | `runtime interval-ms` | drive the rotating demo |
| `-main` | `& args` | serve on `127.0.0.1:8787` |

## Status

beta — the full plane stack (cedar + admission + vm-pool + fanout + ledger +
rap) runs in-process, and the sandbox boundary is real: every admitted run
boots or reuses a KVM microVM and executes argv in-guest, surfacing the
guest's exit code and stdout. Egress is enforced by the Cedar gate; guest
network itself is default-deny, and per-CIDR nftables allow-listing is not yet
wired.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [glimmer-datastar](https://github.com/jolt-lang/glimmer-datastar) — the Datastar server
- [ring-chez-adapter](https://github.com/jolt-lang/ring-chez-adapter) — the HTTP/SSE adapter
- [faraday-core](../core) — the plane this dashboard peers with
