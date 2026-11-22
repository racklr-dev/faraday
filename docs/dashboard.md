# jolt-plane dashboard — Datastar + Rocket Flow control-plane UI (plan)

Status: **plan**. This is the design for a browser dashboard that manages the
jolt control plane (jolt-plane) and the agent threads it drives. The server owns
all state; the browser is a thin hypermedia client. No JSON REST API, no SPA
build step.

## Why Datastar + Rocket Flow

- **Datastar** is a hypermedia framework: the server streams HTML fragments and
  reactive signals over SSE (`text/event-stream`), and the browser applies them
  with `data-*` attributes. State lives in one place — the jolt daemon — so the
  dashboard cannot drift from the plane.
- **Rocket** (Datastar's web-component API) defines custom elements
  (`rocket('agent-node', …)`) with typed props, so each node/panel is a
  server-rendered, client-reactive component.
- **Rocket Flow** is Datastar's port of React Flow: the server owns the graph
  (nodes + edges), Rocket renders the canvas (pan/zoom/drag), and client drags
  are optimistic until the server broadcasts the snapped coordinates. That is
  exactly the agent topology — threads → invocations → sandboxes — streamed
  live and consistent across tabs.

## What the dashboard manages (the real API surface)

| concern | library | read surface | write surface |
|---|---|---|---|
| gate | jolt-plane | `gate`, `decide`, `stats` | `new-plane`, `admit`, `release`, `close!` |
| runner | jolt-plane | `run-sandbox!`, `run-all!` | `new-runner`, `close-runner!` |
| tool server | jolt-rap | `invoke`, `subscribe`, `on-result` | `invoke`, `cancel`, `close`, `close!` |
| warm VMs | jolt-vm-pool | `stats` `{:warm :total :snapshots}` | `acquire`, `release`, `hibernate`, `restore` |
| threads | jolt-fanout + jolt-ledger | `history`, `last-seq` | `spawn`, `inherit`, `report`, `yield` |
| cache | jolt-memo | `stats` `{:entries :bytes}`, `get` | `put!`, `delete!` |
| budget | jolt-admission | `stats` `{:budget :used :granted :waiting}` | `acquire!`, `release!` |
| policy | jolt-cedar | `authorized?`, `validate` | validate before apply |

## Architecture

```text
Browser (thin client — no build step)
  ├─ datastar-rocket.js   (Datastar + Rocket, vendored)
  ├─ HTML shell: topbar + sidebar + panels   (SSE-merged fragments)
  └─ <rocket-flow> canvas (agent topology: drag/zoom optimistic, server-snapped)
        ▲  SSE: datastar-merge-fragments / merge-signals / remove-fragments
        │  (text/event-stream, one stream per tab)
        ▼  HTTP GET / POST (data-on:click → action endpoint, signals as form body)
jolt-dashboard  (Clojure, jolt — new repo racklr-dev/jolt-dashboard)
  ├─ http.clj    : routes  /  /events  /actions/*  /assets
  ├─ sse.clj     : Datastar SSE emitter (Clojure)
  ├─ board.clj   : one atom of plane state, sampled + diffed + broadcast
  ├─ views/*.clj : Hiccup fragments per panel
  └─ actions.clj : POST handlers that call the plane/rap/pool below
        │  (in-process function calls — the dashboard is a peer, not a client)
        ▼
  jolt-plane · jolt-rap · jolt-vm-pool · jolt-fanout+jolt-ledger ·
  jolt-memo · jolt-admission · jolt-cedar
```

The dashboard composes the libraries in-process; it is not a separate service
talking to the plane over the wire. That keeps the budget/policy gate
authoritative and lets the UI stream the same objects the tests assert.

## Board atom (the single source for the UI)

One `board` atom holds the last sampled snapshot:

```clojure
{:plane   {:stats <admission stats> :policy {:policies [...] :schema ...}}
 :pool    {:warm n :total n :snapshots n}
 :threads {thread-id {:kind :spawn :label "run_shell" :status :running
                      :parent parent-id :last-seq n}}
 :calls   {call-id {:thread-id .. :name ..
                    :status :running|:ok|:error|:cancelled|:denied
                    :segments [..] :exit-code n}}
 :sandboxes {request-id {:image .. :status :booting|:live|:released}}
 :ledger  [...latest messages...]
 :memo    {:entries n :bytes n}
 :activity [...recent events...]}
```

A sampler runs every 250ms: it calls `plane/stats`, `pool/stats`, `memo/stats`,
the rap call map (via a hook the dashboard registers), and the ledger's recent
messages, then diffs against the previous board. Only changed fragments are
emitted (selector-scoped), so N sandboxes do not mean N redraws — only the
rows/nodes that changed.

## Datastar wire protocol (the only client/server contract)

The Clojure emitter produces SSE frames:

```text
event: datastar-merge-fragments
data: {"fragments":"<div id=\"budget\">…</div>","mergeMode":"morph","selector":"#budget"}

event: datastar-merge-signals
data: {"signals":{"poolWarm":2,"granted":6,"waiting":0}}

event: datastar-remove-fragments
data: {"selector":"#call_123"}

event: datastar-execute-script
data: {"script":"…","autoRemove":true}
```

- `datastar-merge-fragments` — morph/append a server-rendered fragment into a selector.
- `datastar-merge-signals` — push a batch of reactive signals (`$board.poolWarm`, …).
- `datastar-remove-fragments` — drop a finished element (e.g. a cancelled row).
- `datastar-execute-script` — rare imperative escape (e.g. snap the Rocket canvas to authoritative coords).

The shell carries the initial `data-signals` blob; the SSE stream keeps them
fresh. Actions POST the current signals back (Datastar sends them on every
request), so the server sees what the client sees.

## Screens

### Overview (Rocket Flow canvas) — default

- Node kinds: **agent** (thread), **invocation** (call-id), **sandbox** (VM).
- Edges: thread→invocation (spawn), invocation→sandbox (exec), thread→thread (inherit/yield).
- Node color = status: booting=amber, running=blue, ok=green, error=red,
  cancelled=gray, denied=red-striped.
- Node label = `display-as` (e.g. `ls -la`) + live metric (exit-code, cpu/mem).
- Click a node → detail panel; drag to re-arrange (optimistic, server-snapped,
  consistent across tabs).

### Threads (sidebar cards — nodecode channel-card style)

- One card per agent thread: live activity label (`running run_shell`,
  `waiting budget`, `denied by policy`), child count, last message.
- Click → open the thread's invocation list.

### Invocation (detail panel + stream)

- Selected invocation: streaming stdout/stderr segments (text segments append
  live, like the TUI prints), exit code, error code (denied/egress-unknown/exit).
- Controls: cancel, close thread.

### Budget (gauge)

- cpu/mem used vs granted, waiting count, grant history sparkline.

### Policy (editor)

- Cedar policy-set editor + budget + egress registry; `validate` before apply
  (reuses cedar/validate); deny decisions shown with reason.

### Ledger + Memo (inspect)

- Thread message history (replay the tree), memo entry inspector
  (get/delete, entry stats).

## Nodecode web-UI inspiration (mapped)

| nodecode concept | dashboard mapping |
|---|---|
| channel cards with live activity labels | thread cards in the sidebar |
| background evals + status | invocation list with status chips (ok/error/cancelled/denied) |
| streaming transcript | invocation segment stream (text segments append live) |
| fork/team (sessions) | spawn tree — the Rocket Flow graph |
| goal/status | budget gauge + policy decisions + plane stats |
| TUI live activity label | per-node/per-thread status label, SSE-updated |

## Actions (management endpoints)

`POST /actions/*` (Datastar form/signal POSTs; the server replies with the
fragment updates):

- `invoke` → `rap/invoke` (build a Tool Invocation from a form: name, argv,
  image, egress ref, cpu/mem/timeout).
- `cancel` / `close` → `rap/cancel` / `rap/close`.
- `run-sandbox` → `plane/run-sandbox!` (a raw sandbox run through the gate).
- `plane/close` / `plane/reopen` → `close!` then `new-plane` with new config.
- `policy/apply` → validate then reopen the plane with the new policy-set.
- `egress/add` → add an egress contract to the runner registry.
- `memo/delete` → `memo/delete!`.
- `hibernate` / `restore` → pool `hibernate`/`restore`.

## Milestones

| # | slice | proof |
|---|---|---|
| M0 | SSE emitter + shell + board sampler | budget gauge + pool counts live-update in the browser |
| M1 | read-only Rocket Flow topology | headless E2E harness; 6 concurrent invocations appear/settle in the canvas |
| M2 | invocation stream + controls | same 6-invoke run driven entirely from the browser (invoke/cancel/close) |
| M3 | policy + budget editor | deny policy shows the denied terminal + reason in the UI |
| M4 | ledger/memo inspect + config + multi-tab | replay a thread tree; two tabs stay consistent via SSE broadcast |

## Dependencies & open decisions

- **HTTP/SSE server**: hand-rolled on JDK `com.sun.net.httpserver.HttpServer`
  (zero heavy deps, matches the jolt-native ethos) vs Ring+Jetty (middleware +
  static files for free). Recommendation: start on the JDK server; add Ring only
  if static-file/auth middleware outweighs hand-rolling.
- **Rocket/Datastar bundle**: vendor `datastar-rocket.js` (v1.0.5) into
  `assets/`; pin it.
- **Auth**: operator console → bind 127.0.0.1 by default + optional bearer token;
  never a public endpoint, never unvalidated policy/budget apply.
- **Repo**: new `racklr-dev/jolt-dashboard` (dashboard depends on plane, never
  the reverse). The jolt-plane `serve` skeleton is replaced by this server.

## Out of scope (first cut)

- Editing Cedar schema graphically (text editor only).
- Auth/user management (single operator token).
- Historical metrics beyond the ledger (no time-series DB; the board is live-only).
