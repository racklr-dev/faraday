# jolt-dashboard

Browser control-plane dashboard for the jolt plane, built natively on jolt.

- **glimmer-datastar** — the Datastar v1.0 server (reactive ratom render loop,
  tick batching, frame dropping, component cache, id-part diffing over SSE).
- **ring-chez-adapter** — HTTP over BSD sockets (`:strategy :fibers`), with gzip
  middleware that also compresses the SSE stream.
- **jolt-plane** — composed in-process: the dashboard is a peer of the plane,
  not a client, so the budget/policy gate stays authoritative.

The design (screens, board atom, wire contract, milestones) is documented in
[jolt-plane's dashboard plan](../jolt-plane/docs/dashboard.md).

## Run

The dependency tree declares jolt 0.8.28+; the operator's release binary is at
`/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt` (the `~/.local/bin/jolt` build is
still 0.6.5). Cedar's native lib is required to open a plane:

```sh
export JOLT_CEDAR_LIB=/home/jm/projects/jolt/jolt-cedar/native/target/release/libcedar_policy.so
/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt -M:serve
# http://127.0.0.1:8787
```

The default config opens a permit-all plane with a small budget; see
`src/jolt/dashboard/main.clj` for the demo policy.

## Status

M0 — SSE emitter + board sampler + shell. The budget gauge and pool counts
live-update in the browser from a real `jolt.plane`.
