# faraday — control plane to agent machine

Roadmap for turning faraday from a sandbox control plane into a machine that runs, observes, and rewrites its own agent loops. The loop is data; the data compiles to nREPL middleware; the machine runs on the nREPL.

## North star

nodecode's narrow-waist contracts and cells, samizdat's loop-as-data behind a validate/soak/rollback gate, and dirge's hardened step-fn with two-tier FTS5 memory, standing on faraday's sandbox, egress, and ledger moat.

## State today

- The control plane is beta and works end to end: contracts, ledger, memo, pool, rap, fanout, core gate, console.
- faraday/agent has a first loop: hardcoded Clojure driving a scripted agent's tool calls through rap. No manifest, no model step-fn, no memory, no :goal.
- jolt-lang ships jolt.nrepl, a minimal extensible nREPL server (bencode over loopback TCP). Its middleware is (fn [handler] (fn [request] ...)), listed under :nrepl/middleware in deps.edn — the same shape as the faraday loop stack. This is the platform the loop runs on.
- dirge has plugins/nrepl: an agent tool nrepl_eval plus slash commands, delimiter repair, per-eval timeout + interrupt, lazy .nrepl-port discovery. This is the shape of faraday's self-modification channel.

## The machine in three rings

Ring 1 — the moat. Agent-immutable. Already built.
- faraday-contracts: the narrow waist, one define-contract per wire shape
- faraday-ledger: append-only thread messages, per-thread seq, dedup
- faraday-memo: content-addressed cache
- faraday-pool: warm KVM microVMs over jolt-microvm + jolt-oci
- faraday-rap: tool invocation server, streams terminal frames
- faraday-fanout: thread-message fanout
- faraday-core: the gate (budget admission + Cedar policy)
- egress (jolt-tapmasq + jolt-oci): the network boundary

Ring 2 — the loop. Data that compiles to an nREPL server.
- the turn loop is an EDN manifest: states are cells, transitions are predicates over the turn context
- the manifest compiles to a jolt.nrepl middleware chain; each wrap-* is one middleware
- a proposed rewrite passes validate, then soak, then goes live; a bad run re-points the ledger (rollback)

Ring 3 — the surface. Cells the operator and the agent use.
- nREPL cell: inspect and steer the running loop (the loop's home)
- provider cell: route main/critic/summarizer roles to models
- memory cell: two-tier FTS5 memory + post-session orchestrator
- console: observe and steer N agents
- supervisor cell (MVP): watches runs, proposes rewrites through the same gate

## The loop is data, compiled to nREPL middleware

A loop manifest is EDN. It names states, cells, transitions, and the stack. The stack is the :nrepl/middleware vector — symbols that resolve to wrap fns. Reduced example:

    {:states {:orient {:cell :orient}
              :step   {:cell :model-step}
              :invoke {:cell :rap-invoke}
              :judge  {:cell :goal-judge}}
     :nrepl/middleware [faraday.agent.mw.compress/wrap
                        faraday.agent.mw.memory/wrap
                        faraday.agent.mw.router/wrap]}

The canonical form is the EDN (lift and shift subtrees to reorder the pipeline). The compiled form is the middleware chain jolt.nrepl builds at load. Validation covers both: schema the EDN, then dry-run the compiled chain against recorded transcripts.

The manifest is itself a contract at the narrow waist — a section in contracts/SPEC.md, stored in memo/ledger like any other message.

Manifest home: canonical copies live on the classpath under agent/resources/manifests/ (deps.edn :paths). A project-root .faraday/manifests/ override dir is read first and is git-ignored, so steering a running loop is a file edit, not a rebuild.

The agent's self-eval channel: a faraday nrepl_eval tool modeled on dirge's plugin — delimiter repair, per-eval timeout + interrupt, lazy .nrepl-port discovery. It evals new manifest EDN into the running loop, which is how self-modification crosses the gate.

Self-modification is gated, never open:
1. validate — schema check + dry run against recorded transcripts
2. soak — N runs against a test harness, compared with the current loop
3. rollback — a failed soak re-points the ledger to the pre-change head

## The middleware stack

Base handler: invoke one tool call through rap and wait for the terminal frame. Each wrapper is (fn [handler] (fn [request] ...)); request is the turn.

- wrap-history: window the transcript to the context budget; keep the task and the last K entries
- wrap-compress: fold the transcript middle into a structured summary at a threshold (dirge)
- wrap-memory: inject hot memories inline, demote the rest to a breadcrumb index, search on demand (dirge two-tier)
- wrap-router: send the step to the role's provider; escalate a failing step to a stronger role (dirge)
- wrap-repair: fix malformed tool calls, then validate, then circuit-break on a non-progressing loop (dirge)
- wrap-permission: one decision point, op-based rules, a /why trace (dirge)
- wrap-goal: an independent judge rules met/unmet at finalization; the reason re-enters the loop, bounded (dirge + MiMo-Code)

## Build order

Phase 0 — contracts. Done. faraday.contracts define-contract is the narrow waist.

Phase 1 — loop-as-data + middleware spine (nc-96E4).
Accept: run! is a manifest interpreter; the manifest is a contracts section; it compiles to a jolt.nrepl middleware chain; scripted-agent still passes; a manifest can be rewritten as data and gated by validate/soak/rollback.

Phase 2 — robust step-fn + provider roles (nc-A5BB).
Accept: a model step-fn is interchangeable with scripted-agent; repair → validate → escalate → circuit-break shown on a weak model; role aliases route to different providers.

Phase 3 — memory + checkpoint/resume (nc-9461).
Accept: hot memories inline, the rest demote to a breadcrumb index; FTS5 over ledger+memo; a durable checkpoint restores live state via thread-id; the post-session orchestrator extracts learnings.

Phase 4 — :goal stop-condition (nc-F82A).
Accept: run! honors a :goal predicate or command, stops when it passes, reports met/unmet; a mis-stated goal cannot spin forever.

Phase 5 — operator harness + supervisor MVP (nc-B65C).
Accept: launch N agents from the pool, hand them a shared goal, steer mid-run with directives and :goal, observe via ledger/memo/console; the supervisor cell watches runs and proposes rewrites through the gate.

Phase 6 — self-evolution (past MVP).
The supervisor grows from proposer to applier; nREPL becomes the sole steering surface. Jujutsu eval lands here.

## VCS: forgejo now

faraday lives on github.com/racklr-dev/faraday (branch main, monorepo, :local/root deps). Migrate the canonical remote to a self-hosted Forgejo instance before Phase 1; the checkout is already here (/home/jm/projects/forgejo). Keep github as a read-only public mirror if that visibility is wanted; otherwise archive it. No code change — Forgejo speaks git.

- Jujutsu: git-compatible working copy, op-log, cheap global undo; its undo maps to the rollback gate. Evaluate after Phase 1, when the loop rewrites itself.
- Sapling: same niche, smartlog UI. Fallback if jujutsu's git interop chafes.
- Mononoke: Mercurial server, heavy. Not for this repo.

## Extraction policy

faraday owns the agent-harness business logic: the loop manifest, the step-fn, memory, goal, supervision, and the rap/ledger/memo/contracts moat. jolt-* libraries are utilities only — jolt-microvm, jolt-oci, jolt-tapmasq, and jolt.nrepl itself are dependencies, not homes for harness logic.

Extract to a jolt-* library only when the piece is a self-contained utility that composes for the wider community (for example a generic nREPL middleware kit), never to make faraday's own logic reusable-by-default. If the extraction would drag faraday-specific contracts along, it stays in faraday.

## License

faraday is EPL-2.0. Port nodecode mechanics freely (MIT). Take ideas and patterns only from samizdat and dirge (GPL-3), never code. dirge's nREPL plugin is Janet and GPL-3; faraday reimplements the behavior (delimiter repair, timeout+interrupt, lazy port discovery) in Clojure.

## Settled decisions

1. The loop runs on the nREPL. The EDN manifest is canonical; it compiles to a jolt.nrepl middleware chain. Lift/shift is EDN reordering; validation runs on both forms.
2. Manifest home is classpath (agent/resources/manifests/) with a git-ignored .faraday/manifests/ override dir read first.
3. The supervisor cell is in the MVP (Phase 5), as a proposer through the gate. Autonomous applying is later.
4. Forgejo now, before Phase 1; jujutsu evaluated after Phase 1.

## Still open

- Which Forgejo host/instance is canonical, and whether the github mirror stays public.
- Whether the compiled middleware chain is rebuilt per-run or cached by manifest hash.
