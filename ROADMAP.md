# faraday — control plane to agent machine

Roadmap for turning faraday from a sandbox control plane into a machine that runs, observes, and rewrites its own agent loops.

## North star

nodecode's narrow-waist contracts and self-modifying cells, samizdat's loop-as-data behind a validate/soak/rollback gate, and dirge's hardened step-fn with two-tier FTS5 memory, all standing on faraday's sandbox, egress, and ledger moat.

## State today

- The control plane is beta and works end to end: contracts, ledger, memo, pool, rap, fanout, core gate, console.
- faraday/agent has the first loop: hardcoded Clojure that drives a scripted agent's tool calls through rap. No model step-fn, no data manifest, no memory, no :goal.
- The board tracks this under epic nc-0901 'best-of-all-worlds agent synthesis': 5 open, 1 done (nc-9891 contracts).

## The machine in three rings

Ring 1, the moat. Agent-immutable. Already built.
- faraday-contracts: the narrow waist, one define-contract per wire shape
- faraday-ledger: append-only thread messages, per-thread seq, dedup
- faraday-memo: content-addressed cache
- faraday-pool: warm KVM microVMs over jolt-microvm + jolt-oci
- faraday-rap: tool invocation server, streams terminal frames
- faraday-fanout: thread-message fanout
- faraday-core: the gate (budget admission + Cedar policy)
- egress (jolt-tapmasq + jolt-oci): the network boundary

Ring 2, the loop. Data, not code. This is the new work.
- the turn loop is an EDN manifest, a state machine whose states are cells and whose transitions are predicates over the turn context
- the loop is also a middleware stack: history, compressor, memory, provider router, step repair, permission, goal judge each wrap the base rap invoke step
- a proposed rewrite passes validate, then soak, then goes live; a bad run re-points the ledger to the pre-change head (rollback)

Ring 3, the surface. Cells the operator and the agent use.
- nREPL cell: inspect and steer the running loop
- provider cell: route main/critic/summarizer roles to different models
- memory cell: two-tier FTS5 memory + post-session orchestrator
- console: observe and steer N agents
- supervisor cell (later): watches runs and proposes loop rewrites

## One big idea: the loop is data and a stack

A loop manifest is an EDN map. It names the states, the cells, the transitions, and the middleware stack. Example, reduced:

    {:states {:orient {:cell :orient}
              :step   {:cell :model-step}
              :invoke {:cell :rap-invoke}
              :judge  {:cell :goal-judge}}
     :stack  [{:wrap :compress :with {:threshold 0.8}}
              {:wrap :memory  :with {:inline 20}}
              {:wrap :router  :with {:roles {:critic :anthropic}}}]}

A cell declares what it is: pure or effectful, its schema, its deps. It installs and uninstalls cleanly. That is nodecode's define-cell idea, ported. The stack is a vector of wrap maps; reordering the loop is moving EDN subtrees, not editing code. Lifting and shifting a jolt tree reorders the pipeline.

The manifest is itself a contract at the narrow waist. It gets a section in contracts/SPEC.md, so it crosses the validate/soak boundary and stores in memo/ledger like any other message.

Self-modification is gated, never open:
1. validate — schema check plus a dry run against recorded transcripts
2. soak — N runs against a test harness, compared with the current loop
3. rollback — on a failed soak, the ledger re-points to the pre-change head

## The middleware stack

Base handler: invoke one tool call through rap and wait for the terminal frame.

- wrap-history: window the transcript to the context budget; keep the task and the last K entries
- wrap-compress: fold the transcript middle into a structured summary when the ratio crosses a threshold (dirge)
- wrap-memory: inject hot memories inline, demote the rest to a breadcrumb index, search on demand (dirge two-tier)
- wrap-router: send the step to the role's provider; escalate a failing step to a stronger role (dirge)
- wrap-repair: fix malformed tool calls before they hit the tool, then validate, then circuit-break on a non-progressing loop (dirge)
- wrap-permission: one decision point, op-based rules, a /why trace (dirge)
- wrap-goal: at each finalization an independent judge rules met/unmet; the reason re-enters the loop, bounded (dirge + MiMo-Code)

## Build order

Phase 0 — contracts. Done. faraday.contracts define-contract is the narrow waist.

Phase 1 — loop-as-data + middleware spine (nc-96E4).
Accept: run! is a manifest interpreter; the manifest is a contracts section; scripted-agent still passes; a manifest can be rewritten as data and gated by validate/soak/rollback.

Phase 2 — robust step-fn + provider roles (nc-A5BB).
Accept: a model step-fn is interchangeable with scripted-agent; repair then validate then escalate then circuit-break shown on a weak model; role aliases route to different providers.

Phase 3 — memory + checkpoint/resume (nc-9461).
Accept: hot memories inline, the rest demote to a breadcrumb index; FTS5 over ledger+memo; a durable checkpoint restores live state via thread-id identity; the post-session orchestrator extracts learnings.

Phase 4 — :goal stop-condition (nc-F82A).
Accept: run! honors a :goal predicate or command, stops when it passes, reports met/unmet; a mis-stated goal cannot spin forever.

Phase 5 — operator harness (nc-B65C).
Accept: launch N agents from the pool, hand them a shared goal, steer mid-run with directives and :goal, observe via ledger/memo and the console.

Phase 6 — self-evolution (later).
The supervisor cell watches runs and proposes loop rewrites through the same gate. nREPL lands here too.

## VCS: forgejo, jujutsu, sapling, mononoke

faraday lives on github.com/racklr-dev/faraday, branch main, a monorepo with :local/root deps.

- Forgejo: drop-in git replacement, self-hosted, already checked out here (/home/jm/projects/forgejo). Adopt it when self-hosting matters. No code change needed.
- Jujutsu: git-compatible working copy with an op-log and cheap global undo. Its undo maps well to the rollback gate. Evaluate it for the loop-rewrite workflow after Phase 1.
- Sapling: same niche as jujutsu, smartlog UI. Fallback if jujutsu's git interop chafes.
- Mononoke: Mercurial server, heavy. Not worth it for this repo.

Keep git for the MVP. Revisit after Phase 1 when the loop rewrites itself.

## License

faraday is EPL-2.0. Port nodecode mechanics freely (MIT). Take ideas and patterns only from samizdat and dirge (GPL-3), never code.

## Open questions

1. Should the middleware stack be the manifest itself, or a compiled view of it? The former is simpler to lift and shift; the latter is easier to validate.
2. Where does the loop manifest live: in memo, in a new manifest/ dir, or in agent resources? It should be one canonical home.
3. Is the supervisor cell (autonomous loop rewriting) in the MVP or later? Phase 6 is my read; say if you want it sooner.
4. Forgejo now or after the MVP? Migrating is cheap now and churn-free later; your call.

