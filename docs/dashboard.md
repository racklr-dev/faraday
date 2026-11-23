# faraday-core dashboard — Datastar + Rocket Flow control-plane UI (plan)

Status: **plan**. This is the design for a browser dashboard that manages the
jolt control plane (faraday-core) and the agent threads it drives. The server owns
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
- **glimmer-datastar** (jolt-lang, MIT) already implements the Datastar v1.0
  server: `wrap-datastar` renders the handler under a reactive loop, subscribing
  each SSE connection to the `glimmer.ratom` cells its last render dereffed.
  Writes batch per tick, slow clients get the newest frame only, components are
  cached, and changed HTML is diffed down to id-patched parts (DBSP's `D`).
  The dashboard composes it instead of hand-rolling an emitter.

## What the dashboard manages (the real API surface)

| concern | library | read surface | write surface |
|---|---|---|---|
| gate | faraday-core | `gate`, `decide`, `stats` | `new-plane`, `admit`, `release`, `close!` |
| runner | faraday-core | `run-sandbox!`, `run-all!` | `new-runner`, `close-runner!` |
| tool server | faraday-rap | `invoke`, `subscribe`, `on-result` | `invoke`, `cancel`, `close`, `close!` |
| warm VMs | faraday-pool | `stats` `{:warm :total :snapshots}` | `acquire`, `release`, `hibernate`, `restore` |
| threads | faraday-fanout + faraday-ledger | `history`, `last-seq` | `spawn`, `inherit`, `report`, `yield` |
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
faraday-console  (Clojure, jolt — new repo racklr-dev/faraday-console)
  ├─ handler.clj : routes  /  /events  /actions/*  /assets
  ├─ board.clj   : one glimmer ratom of plane state, resampled every 250ms
  ├─ views/*.clj : Hiccup fragments per panel (cached components)
  └─ actions.clj : POST handlers that call the plane/rap/pool below
        │  served by ring-chez-adapter (BSD sockets, :strategy :fibers)
        │  SSE + reactive diff/patch by glimmer-datastar (wrap-datastar)
        ▼
  faraday-core · faraday-rap · faraday-pool · faraday-fanout+faraday-ledger ·
  jolt-memo · jolt-admission · jolt-cedar
```

The dashboard composes the libraries in-process; it is not a separate service
talking to the plane over the wire. That keeps the budget/policy gate
authoritative and lets the UI stream the same objects the tests assert.

## Board atom (the single source for the UI)

One `board` ratom (a `glimmer.ratom` cell) holds the last sampled snapshot:

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

A sampler fiber runs every 250ms: it calls `plane/stats`, `pool/stats`,
`memo/stats`, the rap call map (via a hook the dashboard registers), and the
ledger's recent messages, then `swap!`s the board ratom. `wrap-datastar`
re-renders each dirty connection once per tick, diffs against its last frame,
and emits only the changed id-patched parts — cached components render once per
change and are shared across tabs — so N sandboxes do not mean N redraws.

## Datastar wire protocol (the only client/server contract)

glimmer-datastar emits the Datastar v1.0 SSE frames (`text/event-stream;
charset=utf-8`):

```text
event: datastar-patch-elements
id: 22503D92
data: selector body
data: mode inner
data: namespace html
data: elements <div><section id="budget">…</section>…</div>
data: useViewTransition false

event: datastar-patch-signals
data: {"signals":{"poolWarm":2,"granted":6,"waiting":0}}
```

- `datastar-patch-elements` — merge server-rendered HTML into a selector (modes
  `inner`/`outer`); the render loop sends only the id-patched parts when the
  page changed around them, the whole page otherwise.
- `datastar-patch-signals` — push a batch of reactive signals
  (`$board.poolWarm`, …).
- Actions arrive with the `datastar-request: true` header and their signals
  parsed into `:jolt.datastar/signals`; handlers reply `patch-signals` or a
  fragment update.
- The client SDK also understands `datastar-remove-fragments` and
  `datastar-execute-script`, emitted by hand when a finished element must drop
  or the Rocket canvas must snap to authoritative coordinates.

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

- **HTTP + SSE server**: `ring-chez-adapter` (BSD sockets, `:strategy :fibers`,
  static files + gzip middleware; gzip carries its window across SSE frames) +
  `glimmer-datastar` (the Datastar v1.0 server: reactive ratom render loop,
  tick batching, frame dropping, component cache, id-part diffing). Pin
  `glimmer-datastar` v0.1.12, `ring-chez-adapter` v0.10.1, and `router` v0.0.3
  (reitit-compatible trie) once actions multiply.
- **Rocket/Datastar client bundle**: vendor `datastar-rocket.js` (v1.0.5) into
  `assets/`; pin it. Rocket Flow is the canvas half; glimmer-datastar is the
  server half — they meet at the SSE contract above.
- **Auth**: operator console → bind 127.0.0.1 by default + optional bearer token;
  never a public endpoint, never unvalidated policy/budget apply.
- **Repo**: new `racklr-dev/faraday-console` (dashboard depends on plane, never
  the reverse). The faraday-core `serve` skeleton is replaced by this server.

## Out of scope (first cut)

- Editing Cedar schema graphically (text editor only).
- Auth/user management (single operator token).
- Historical metrics beyond the ledger (no time-series DB; the board is live-only).
