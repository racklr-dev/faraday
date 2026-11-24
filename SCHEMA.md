# faraday harness database schema

The harness DB is faraday's own SQLite store for the state that is neither the append-only ledger nor the content-addressed memo: the taskboard (kanban), the project/folder scope tree, and the pre/post hooks those scopes inject into the loop. Every row in it is data, and the whole point is that the loop reads it back as loop-as-data.

## Where it lives

- One DB per project root: `.faraday/harness.db` (git-ignored, sibling of `.faraday/manifests/`).
- Same storage conventions as faraday-ledger: SQLite via `jolt-lang/db` (`db.sqlite`), plain SQL, one connection per store, a per-store lock, WAL, a busy timeout, and idempotent `CREATE TABLE IF NOT EXISTS` on open. No linear `user_version` chain: each table owns its schema, so a feature can be added later without renumbering (dirge's issue_db lesson).
- Module: `faraday.harness`, a new member of the monorepo next to ledger/memo. Its wire shapes get entries in contracts/SPEC.md like every other store.

## The honesty invariant

The board, the scopes, and the hooks are one system because of a single rule: **a turn may report green only if every hook the turn's scope expected actually ran and was recorded.** The DB holds both sides of that check:

- `hooks` — what *should* run (the expectation, reified as data)
- `hook_runs` — what *did* run (the record, one row per execution)

A hook that was expected but left no row, or a hook that ran but left no row, fails the turn regardless of every other green. This is faraday's fix for the dirge failure class where a check ran, reported success, and could not have failed for the reason that mattered: the measurement layer is itself a table, audited like the ledger.

## Tables

### projects

One row per project root.

    create table if not exists projects (
      id               text primary key,        -- stable id
      root_path        text not null unique,    -- absolute canonical root
      name             text not null,
      default_manifest text not null,           -- base loop manifest: agent/resources/manifests/<name>.edn
      created_at       text not null,
      updated_at       text not null
    );

### scopes — the project/folder metadata schema

A scope is a folder (the root scope has `rel_path` = empty string). Nesting is path-prefix: a hook on `src/` applies to `src/db/` and everything beneath it. A deeper scope adds to its ancestors; it does not replace them (whether it may shadow by cell+position+kind is Still-open in ROADMAP).

    create table if not exists scopes (
      id         text primary key,
      project_id text not null references projects(id),
      rel_path   text not null,                 -- '' for root; forward slashes; e.g. src/db
      is_root    integer not null default 0,
      meta       text not null default '{}',    -- EDN bag: language, build-tool, test-cmd, lint-cmd...
      created_at text not null,
      updated_at text not null,
      unique (project_id, rel_path)
    );
    create index if not exists scopes_project on scopes(project_id, rel_path);

`meta` is the per-folder knowledge the loop needs before any model step: test command, linter, build tool, entry point, coverage gate. Storing it in the DB (not a magic file) means the loop can look it up by path and hooks can reference it.

### hooks — one row is one pre/post middleware insertion

`how`, `where`, and `why` are first-class columns. `hook add` refuses a row with any of the three empty.

    create table if not exists hooks (
      id         text primary key,              -- hook-<hash>
      project_id text not null references projects(id),
      scope_id   text not null references scopes(id),
      cell       text not null,                 -- orient|step|invoke|judge|finalize|* (every cell)
      position   text not null,                 -- pre | post
      kind       text not null,                 -- check|lint|slop-cop|fast-test|gate|contract|soak
      how        text not null,                 -- EDN: middleware symbol | command map | predicate
      why        text not null,                 -- what it guards, and what would make it fail
      gate       text not null default 'advisory',  -- advisory | blocking | fail-open
      priority   integer not null default 0,    -- within (cell, position): lower runs first
      enabled    integer not null default 1,
      timeout_ms integer,
      created_at text not null,
      updated_at text not null
    );
    create index if not exists hooks_scope on hooks(scope_id, cell, position, priority);

- **where** = `scope_id` + `cell` + `position`.
- **how** = the mechanism, an EDN form the compiler turns into one middleware wrapper:
  - `{:middleware faraday.agent.mw.slop-cop/wrap}` — in-process middleware symbol
  - `{:command [clj-kondo --lint src/db] :timeout-ms 8000}` — out-of-process gate (exit 0 = pass)
  - `{:predicate faraday.harness.pred/honest-diff}` — EDN predicate over the turn context
  - `{:contract :thread-message}` — narrow-waist schema check against contracts/SPEC.md
- **why** = the reason, and it must name the failure it can produce. Example: *blocks claudism prose; fails when the step output contains delve or the known-bad fixture phrase*.
- **gate** = what the loop does on fail: `advisory` records and continues; `blocking` stops the cell and routes to repair; `fail-open` means a hook that cannot start (command missing, timeout) never blocks — dirge's hooks rule, but explicit per hook here.

### hook kinds

| kind | fires | typical `how` | a fail means |
|---|---|---|---|
| `check` | pre, any cell | predicate over turn context | invariant violated |
| `lint` | pre/post `:step` | clippy / clj-kondo / fmt command | static defect or drift |
| `slop-cop` | post `:step` | prose / hallucinated-API / no-op-diff cop | model slop slipped through |
| `fast-test` | post `:step` | quick unit gate under N seconds | regression |
| `gate` | pre/post, any | arbitrary shell command | the named project gate failed |
| `contract` | pre `:invoke` / post `:finalize` | narrow-waist schema validate | wire shape broke |
| `soak` | post `:finalize` | N-run A/B vs recorded transcript | rewrite regressed |

### hook_runs — the honesty ledger

Every hook execution lands here — pass, fail, skip, error, timeout. The *absence* of a row is itself a finding.

    create table if not exists hook_runs (
      id         text primary key,              -- run id
      hook_id    text not null references hooks(id),
      task_id    text,                          -- task that started the turn, when any
      turn_id    text not null,                 -- ledger thread id the turn ran under
      cell       text not null,
      position   text not null,
      verdict    text not null,                 -- pass | fail | skip | error | timeout
      exit_code  integer,
      output_sha text,                          -- content hash: memo + reporter audit
      ms         integer not null,
      ran_at     text not null
    );
    create index if not exists hook_runs_turn on hook_runs(turn_id, hook_id);

### tasks — the kanban

dirge's issue_db shape, faraday-ified: a task points at a scope, and `task start` resolves that scope's nested hooks into loop-as-data.

    create table if not exists tasks (
      id         text primary key,              -- faraday-<hex>
      project_id text not null references projects(id),
      scope_id   text,                          -- where the work happens; hooks resolve from here
      title      text not null,
      body       text not null default '',
      status     text not null default 'open',  -- open | in_progress | blocked | done | cancelled
      priority   text not null default 'normal',-- high | normal | low
      epic_id    text,                          -- self-ref; an epic is a task with children
      manifest   text,                          -- optional EDN loop-manifest override for this task
      assignee   text,                          -- agent / session id once in_progress
      dedup_key  text,
      created_at text not null,
      updated_at text not null,
      closed_at  text
    );
    create index if not exists tasks_scope on tasks(scope_id, status, priority);

`epic_id` keeps the board flat (dirge-proven): an epic is just a task with children, and a terminal child still rolls up to its parent. `manifest` is the per-task loop-as-data override; it splices over the project's `default_manifest`, so one task can name its own cells or middleware without forking the board.

## Resolution: task start to loop-as-data

`task start faraday-3f9a` is one query plus one fold:

1. Read the task, its scope, and the project's default manifest.
2. Select every enabled hook whose scope `rel_path` is a prefix of the task's scope `rel_path`, ordered by path depth (shallowest first), then `cell`, `position`, `priority`.
3. Group into `{cell {position [hook ...]}}`.
4. Splice each hook's `how` into the manifest as pre/post middleware around its cell.

The result is the fully reified loop-as-data for that task:

    {:task  :faraday-3f9a        ; id strings are shown as keywords here
     :scope :src/db
     :cells {:orient {:cell :orient}
             :step   {:cell :model-step}
             :invoke {:cell :rap-invoke}
             :judge  {:cell :goal-judge}}
     :hooks {:orient {:pre  [:hook-0f2]               ; root scope: contract check
                      :post []}
             :step   {:pre  [:hook-3a1 :hook-9c4]     ; src: clj-kondo, src/db: fast-test
                      :post [:hook-7d0]}              ; src: slop-cop
             :judge  {:pre  [:hook-1b7]               ; blocking gate
                      :post []}}
     :nrepl/middleware
     [faraday.agent.mw.compress/wrap
      faraday.agent.mw.hooks/wrap     ; resolves :hooks above at each cell boundary
      faraday.agent.mw.memory/wrap
      faraday.agent.mw.router/wrap]}

The `:hooks` map *is* the description of what runs before and after each cell; the `:nrepl/middleware` vector is its compiled form. `faraday.agent.mw.hooks/wrap` is the one generic middleware that consults the `:hooks` map at each cell boundary — individual hooks stay data, not bespoke middleware.

## A hook must be able to fail

`hook add` refuses to register a hook unless `why` states its failure mode, and for `check` / `predicate` / `command` hooks it runs the hook once against a known-good fixture and once against a known-bad fixture; the two verdicts must differ. A hook that cannot produce `fail` is worse than no hook (dirge's verification discipline), so it is not stored. The known-bad fixture lives in `why`, and the loop replays it when it suspects a gate has gone vacuous.

## Turn finalization: expected is a subset of ran

At finalization the loop compares the reified `:hooks` map with `hook_runs` for the turn: every `blocking` hook must have a `pass` row, and every expected hook must have *some* row. A missing row means a dropped gate, and the turn fails no matter how green everything else looked. That structural guarantee — the reporter cannot silently under-report — is why the board and the hooks live in the same database.
