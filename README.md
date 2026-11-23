# faraday-console

Browser control-plane dashboard for the jolt plane, built natively on jolt.

- **glimmer-datastar** — the Datastar v1.0 server (reactive ratom render loop,
  tick batching, frame dropping, component cache, id-part diffing over SSE).
- **ring-chez-adapter** — HTTP over BSD sockets (`:strategy :fibers`), with gzip
  middleware that also compresses the SSE stream.
- **faraday-core** — composed in-process: the dashboard is a peer of the plane,
  not a client, so the budget/policy gate stays authoritative.

The design (screens, board atom, wire contract, milestones) is documented in
[faraday-core's dashboard plan](../faraday-core/docs/dashboard.md).

## Run

The dependency tree declares jolt 0.8.28+; the operator's release binary is at
`/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt` (the `~/.local/bin/jolt` build is
still 0.6.5). Cedar's native lib is required to open a plane:

```sh
cd /home/jm/projects/jolt/faraday-console
sudo -u jm --preserve-env env HOME=/home/jm \
  JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt \
  JOLT_LIBKRUN_DIR=/tmp/libkrun/target/release \
  JOLT_CEDAR_LIB=/home/jm/projects/jolt/jolt-cedar/native/target/release/libcedar_policy.so \
  PATH=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux:$PATH \
  /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt -M:serve
# http://127.0.0.1:8787
```

In a fresh login shell (after `sudo usermod -aG kvm jm`) the `sudo -u jm
--preserve-env` wrapper is unnecessary — plain `jolt -M:serve` suffices.

The default config opens an identity-scoped plane (alice/bob permitted, others
denied by default; an egress forbid drops anything touching 8.8.8.8/32) and
drives a rotating demo through the real RAP run-hook, so every view has live
data.

## Status

M1 — full control-plane MVP. Seven views (Overview, Runs, Agents, Threads,
VMs, Policy, Egress) switched client-side by a `$view` signal; a live SSE
board (plane stats, gate decisions, runs, calls, threads, sandboxes); a Cedar
dry-run simulator (identity + egress); and the actions cancel, ad-hoc run, and
thread drill-in. The full plane stack (cedar + admission + vm-pool + fanout +
ledger + rap) runs in-process, and the sandbox boundary is REAL: every
admitted run boots (or reuses a warm) KVM microVM — alpine:3.20 via
jolt-microvm + libkrun 2.0 — and executes argv in-guest over SSH, surfacing
the guest's exit code and stdout. Egress contracts are enforced by the Cedar
gate; guest network itself is default-deny (libkrun ships no route), with
nftables allow-listing still on the v1.1 backlog. See docs/ux-plan.md.
