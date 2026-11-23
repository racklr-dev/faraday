# faraday-console — Control Plane UX Plan (v1 draft for iteration)

Grounding: the plane is one spine. Every screen is a lens on it.

```
RAP (faraday-rap)   tool invocation ──┐
                                   ▼
runner (faraday.core)  new-runner / run-sandbox! / run-all!
   ├─ gate    jolt-cedar (authorize) → jolt-admission (admit under budget)
   ├─ fanout  faraday-fanout + faraday-ledger    thread spawn/report tree
   ├─ pool    faraday-pool + jolt-microvm  warm KVM sandboxes (libkrun)
   └─ egress  egress-id → Egress Contract (CIDRs/DNS/host-gateway)
```

A run flows: request → gate(cedar → admission) → pool acquire → microvm exec → release → report.
The dashboard makes that flow visible (observe) and editable (control).

---

## 1. Principles

1. **The gate is the hero.** Budget + policy + egress in one visible decision point.
2. **Live-first, but every live thing is durable.** SSE for the now; the ledger
   (faraday-ledger) is the audit trail. Nothing live-only that we can't scroll back.
3. **One click from stream to source.** Every row (run, invocation, thread msg)
   deep-links to the exact policy/permit/ledger record that produced it.
4. **Power over wizards.** Cedar is the security ground truth: edit raw source,
   validate, simulate — never a form that hides what the policy says.
5. **Read-mostly with sharp, safe writes.** Cancel a call, drain a pool, add a
   policy. Destructive ops always confirm + are recorded as ledger system msgs.

---

## 2. Information architecture

Persistent chrome (all views):

```
┌─────────────────────────────────────────────────────────────────────┐
│ ● plane:live   cpu ▓▓▓░░ 2/4   mem ▓░░░░ 512/4096   granted 1  wait 3 │
│ [Overview] [Runs] [Agents] [Threads] [VMs] [Policy] [Egress] [Config]│
└─────────────────────────────────────────────────────────────────────┘
  search: thread/request/call/msg  ·  [+ New run]  ·  [⚖ dry-run]   ·  ⋮
```

Eight sections. Left rail (icons + labels), top bar carries the live budget
strip and the two global actions (ad-hoc run, policy dry-run).

---

## 3. Per-view specs

### 3.1 Overview — the gate at a glance

**Purpose:** one-screen health of the whole plane; the landing view.

**Shows:**
- Budget gauges (cpu, mem): used vs budget, granted (active permits), waiting (queue depth).
- Gate decisions sparkline + last N decisions table: request-id, principal, action, resource, decision (allow/deny), cost, ts.
- Pool strip: warm / total / snapshots, per-image counts.
- Live runs: thread, image, argv (truncated), elapsed.
- Recent agent activity: call-id, tool, display-as, status.

**CTAs:** [+ New run] (ad-hoc sandbox form), [⚖ dry-run] (test a request against policy), [drain pool].

```
┌ Overview ─────────────────────────────────────────────────────────┐
│ BUDGET          cpu ▓▓▓░░ 2/4      mem ▓░░░░ 512/4096              │
│                 granted 1   waiting 3                              │
│                                                                    │
│ GATE  last 60s   allow ▁▃▅▂   deny ▁▁▁   error ▁▁▁                 │
│  req_.. Agent::alice exec oci://img@sha  ALLOW  cpu1/mem512  12s   │
│  req_.. Agent::bob   exec oci://img@sha  DENY   cpu1/mem512  11s   │
│                                                                    │
│ POOL    warm 2   total 4   snapshots 1                             │
│         oci://img@sha  live 1 · warm 2 · snap 1                    │
│                                                                    │
│ LIVE    thr_7F  run_shell "pytest -q"   3.1s elapsed               │
│         thr_8C  run_shell "cargo build"  12s elapsed               │
└────────────────────────────────────────────────────────────────────┘
```

*(Rocket Flow canvas lands here later: the gate pipeline as a live graph with
flowing tokens — RAP → gate → pool → exec → ledger.)*

---

### 3.2 Runs — the spine

**Purpose:** every sandbox execution, live and historical; the proof that the
gate→exec→report loop is observable end to end.

**Shows:**
- Filter bar: status (ok/error/denied/cancelled), agent/thread, image, time range, search.
- Run table (SSE live-append): request-id, thread-id, principal, image, argv, egress ref, cpu/mem, status, exit-code, duration, ts.
- Drill-in trace panel (full lifecycle):
  1. request map
  2. gate decision — which Cedar policy permitted/denied, permit id, cost
  3. admission — granted under budget
  4. pool acquire — sandbox id, image, warm/hit vs cold boot
  5. exec — stdout/stderr segments
  6. release + report — ledger message seq

**CTAs:** re-run (clone request), cancel (if live), download stdout/stderr, open owning thread.

```
┌ Runs ───────────────────────────────────────────────────────────────┐
│ filter: [status ▾] [agent ▾] [image ▾] [search…]                    │
│ req_01J8  thr_7F  alice  run_shell  oci:img  ▸ ok     0  2.3s  12:01 │
│ req_01J7  thr_8C  bob    run_tests  oci:img  ▸ denied –  –    12:00 │
│ req_01J6  thr_8C  bob    run_tests  oci:img  ▸ error  2  30.0s 11:59 │
│                                                                      │
│ ▸ req_01J8 trace:                                                    │
│   gate   ALLOW  policy #3 permit(Agent=="alice", exec)  cpu1/mem512  │
│   admit  granted (used 2/4 cpu)                                      │
│   pool   acquire sandbox sbx_9  warm hit                              │
│   exec   $ pytest -q …  exit 0                                       │
│   report ledger thr_7F seq 14                                        │
└──────────────────────────────────────────────────────────────────────┘
```

---

### 3.3 Agents — hooking agents (RAP/MCP)

**Purpose:** watch and control tool invocations in real time. This is the
operator's hook into agents running through the plane.

**Shows:**
- Agent registry: identity (Cedar principal), last-seen, thread count, active calls.
- Live invocation stream: call-id, agent, thread, tool name, display-as, status, streamed segments.
- Invocation detail: args, terminal result, exit-code, error, full segment stream.

**CTAs:** cancel invocation, invoke (manual tool call), close agent.

```
┌ Agents ────────────────────────────────────────────────────────────┐
│ AGENTS        alice  ● live  thr 2  calls 1                        │
│               bob    ◌ idle  thr 1  calls 0                        │
│                                                                     │
│ LIVE CALLS    call_9  alice  run_shell  "pytest -q"   ▸ streaming  │
│               call_8  bob    run_shell  "cargo build" ▸ ok  exit 0  │
│                                                                     │
│ ▸ call_9:   args {argv [pytest -q]}                                │
│   segment  text  "collecting… 14 tests"                            │
│   segment  text  "14 passed in 2.1s"                               │
│   [cancel]                                                          │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 3.4 Threads — durable agent state (ledger)

**Purpose:** the tree of Thread Messages; where yield/snapshot (hibernate-to-data)
becomes user-visible and resumable.

**Shows:**
- Thread list: thread-id, root ts, last seq, last kind, msg count.
- Thread detail: message tree seq-ordered (spawn/inherit/report/yield/user-input/system);
  each node expandable to payload; report shows status+segments; yield shows snapshot-ref.

**CTAs:** inspect thread, view snapshot, (v1.1: resume from snapshot / replay from seq).

```
┌ Threads ───────────────────────────────────────────────────────────┐
│ THREADS       thr_7F  root 12:01  seq 14  last report  15 msgs    │
│               thr_8C  root 11:58  seq 9   last yield    9 msgs    │
│                                                                     │
│ ▸ thr_7F                                                           │
│   seq 0  spawn     spec {argv [pytest -q]}                        │
│   seq 1  system    event boot                                     │
│   seq 14 report    status ok  segments [text "14 passed"]          │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 3.5 VMs — warm pool management

**Purpose:** manage the KVM fleet (faraday-pool): warm set, snapshots, drain.

**Shows:**
- Pool summary: warm / total / snapshots.
- Per-image breakdown: image → live / warm / total.
- Sandbox table (needs vm-pool list fn, see §5): sandbox id, image, state, age.
- Snapshot list: snapshot-ref, image, size, ts.

**CTAs:** configure pool (image, max-live, max-warm), warm N, drain (evict), hibernate → snapshot, restore snapshot.

```
┌ VMs ───────────────────────────────────────────────────────────────┐
│ POOL   warm 2   total 4   snapshots 1        [drain] [warm +1]    │
│   oci://img@sha   live 1  warm 2  snap 1                           │
│                                                                     │
│ SANDBOXES   sbx_9  oci:img  ● live   age 3m                        │
│             sbx_8  oci:img  ◐ warm   age 1m                        │
│             sbx_7  oci:img  ○ snap   age 40s                       │
│                                                                     │
│ SNAPSHOTS   snap_a1  oci:img  128MiB  12:00   [restore]            │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 3.6 Policy — the security seam (Cedar)

**Purpose:** manage who may do what. Raw Cedar source as ground truth.

**Shows:**
- Policy list: label, Cedar source, syntax-validated status, last-edited.
- Editor: raw Cedar source, live `validate` feedback, diff, save.
- Entities/schema viewer: the entities array + schema JSON (read/edit).
- Simulator (dry-run): feed a request (principal/action/resource/context/cost) → decision + which policy matched.

**CTAs:** add policy, edit, remove, validate, simulate, apply.

```
┌ Policy ────────────────────────────────────────────────────────────┐
│ POLICIES     #1  permit(Agent::"alice", exec, Image::"*");   ✓ ok │
│              #2  permit(Agent::"bob", run_tests, Image::"*"); ✓ ok │
│              #3  forbid(principal, exec, Image::"admin");     ✓ ok │
│                                              [+ add policy]         │
│ ▸ #1 editor:                                                       │
│   permit(principal == Agent::"alice",                              │
│          action == Action::"exec",                                 │
│          resource);                                                │
│   [validate ✓] [save]                                               │
│                                                                     │
│ SIMULATE  principal Agent::"alice"  action exec  image oci:img     │
│           → ALLOW (policy #1)                                      │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 3.7 Egress — the network seam

**Purpose:** per-run network allow/deny (CIDRs, DNS, host gateway).

**Shows:**
- Registry list: egress-id, dns, allow-cidrs, deny-cidrs, host-gateway, default.
- Editor: allow/deny CIDR lists, DNS upstreams, host-gateway toggle.
- Referenced-by: which runs/requests use this egress.

**CTAs:** add, edit, remove, (v1.1: probe — run a sandbox under this egress and try to reach a CIDR).

```
┌ Egress ────────────────────────────────────────────────────────────┐
│ REGISTRY   egr_public  allow 0.0.0.0/0  dns [1.1.1.1]  gw ✓        │
│            egr_none    allow (empty)    dns [1.1.1.1]  gw ✓        │
│                                              [+ add egress]         │
│ ▸ egr_public:                                                      │
│   allow-cidrs [0.0.0.0/0]   deny-cidrs []                          │
│   dns [1.1.1.1]   host-gateway [✓]   default deny                   │
│   [save]                                                            │
└─────────────────────────────────────────────────────────────────────┘
```

---

### 3.8 Config — bootstrap + runtime

**Purpose:** the plane config that shapes everything (mostly bootstrap-time today).

**Shows:** budget (cpu/mem), weights, cedar lib path, pool defaults, runner egress registry, exec-hook.

**CTAs:** view; live-edit only where the layer supports it (policy/egress/pool are live; budget/weights need a plane restart until hot-reload exists).

---

## 4. v1 MVP cut

In scope for the first complete loop (M1):

1. **Shell** — chrome, live budget strip, global search, [+ New run], [⚖ dry-run].
2. **Overview** — gauges, gate decisions, pool strip, live runs, agent activity.
3. **Runs** — list + full trace drill-in. *(the spine; proves observe-ability)*
4. **Agents** — live invocations + cancel + manual invoke. *(agent hooking)*
5. **VMs** — pool summary + configure + drain + hibernate/restore. *(VM management)*
6. **Policy** — list + editor + validate + simulator. *(security policy)*
7. **Threads** — read-only tree. *(durable agent state; resume deferred)*

Deferred to v1.1+: Rocket Flow canvas on Overview; Egress editor + probe; per-VM
sandbox listing (needs vm-pool list fn); thread resume/replay from snapshot;
budget/weights hot-reload.

---

## 5. Read-side API additions the dashboard needs

These are small, generally-useful list/stream fns to add to the libs (or a
thin faraday.console.api on top):

- faraday-pool: `list` → per-sandbox [{:id :image :state :age}] (stats is counts only).
- faraday-ledger: `list-threads` → [{:thread-id :last-seq :last-kind :ts}] (history is per-thread only).
- faraday-rap: `calls` → current invocations + terminal results (invoke/on-result are push-only).
- runner in-flight registry: the dashboard tracks concurrent run-sandbox! futures (run-sandbox! is synchronous; no in-flight view today).
- cedar: policy-level match info from a decision (authorized? gives decision; per-policy match, if cedar exposes it, powers "which policy matched").

---

## 6. Open UX questions to iterate on

1. **Layout:** left rail (8 sections) vs top tabs vs single scroll? Overview wants full width for the Rocket Flow canvas later — does the canvas replace Overview or live beside it?
2. **Home screen posture:** live operations stream, or health summary first? (I lean: health summary + live strip, live stream on Runs/Agents.)
3. **Policy editing:** raw Cedar textarea (power, my rec) vs a form builder (safety)? Cedar source is the ground truth; a builder drifts from it.
4. **Run trace:** side panel (keep the list in view) vs full-page drill-down?
5. **Agent identity:** is "agent" ≡ Cedar principal + thread-id, or do we need a first-class agent registry beyond what rap/ledger give?
6. **Destructive bar:** how aggressive — drain-all, cancel-all, delete-thread? I lean: confirm + ledger-system-msg, no silent mutates.
