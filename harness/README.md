# faraday.harness — the runnable harness

The harness DB (SCHEMA.md) made real: the taskboard, the project/folder scope
tree, the pre/post hooks those scopes inject into the loop, and the honesty
ledger (hook_runs). The same module carries the CLI, `faraday.harness.main`,
which is the runnable surface for all of it.

## Namespaces

- `faraday.harness` — the DB layer: projects, scopes, hooks, hook_runs, tasks;
  `resolve-hooks` (scope -> loop-as-data :hooks map), `task-start` (task ->
  loop-as-data), and the honesty invariant (`expected-ran?`, `turn-green?`).
- `faraday.harness.main` — the CLI plus `run-task!`, the end-to-end honest run.
- `faraday.harness.supervisor` — the self-improvement gate: `improve!` observes a
  loop-manifest run, proposes a data rewrite (default budget-relaxer, `:proposer`
  seam), gates it through validate/soak/rollback, persists the winner to the task,
  and records the attempt in `supervisor_runs`.
- `faraday.harness.model-proposer` — the model-driven `:proposer`: `(fn [manifest
  outcome] -> candidate | nil)`. Posts the manifest + observed outcome to a
  chat-completions model over HTTPS (curl) and returns the model's EDN rewrite,
  or nil on `:no-proposal`. Key from `FARADAY_LLM_API_KEY`/`DEEPSEEK_API_KEY`
  env, then `~/.faraday/settings.json`, then `~/.nodecode/auth.json`; model/base-url
  via `FARADAY_LLM_MODEL`/`FARADAY_LLM_BASE_URL` or the settings file; the
  `:http` fn is a test seam. Throws on missing key or transport/model failure —
  nil is reserved for a model that genuinely declines.

## Use

```sh
# from the repo root
jolt -M:harness -- help
jolt -M:harness -- init            # create .faraday/harness.db + project
jolt -M:harness -- board
jolt -M:harness -- improve <task>  # observe, propose, gate, persist
jolt -M:harness -- improve <task> --proposer model  # model-driven proposal
```

or `bin/faraday` — runnable from anywhere (it injects the :harness alias with
an absolute path, so jolt resolves deps without changing directory):

```sh
./bin/faraday help
```

## The honest run

`run <task-id>` fires the task's resolved hooks around a scripted loop over a
fake sandbox (no KVM), records one `hook_runs` row per hook, and finalizes:
`green?` iff every expected hook ran and every blocking hook passed. Hooks must
be able to fail: `hook add` rejects a command hook whose known-good and
known-bad fixtures produce the same verdict.

## Settings — the configurable harness surface

`faraday` reads `~/.faraday/settings.json` (user) and, when present,
`<project>/.faraday/settings.json` (project, wins), shaped like dirge's
config.json — the same idea as `.claude/settings.json` / `.gemini/settings.json`.
See `harness/settings.example.json` for the full annotated shape. Every key is
optional; `faraday config` prints what resolves, `faraday config --check`
validates references, and `faraday config model-step` shows the escalation
ladder the agent loop will run.

```jsonc
{
  "providers": {
    "deepseek":      { "base_url": "https://api.deepseek.com/v1", "model": "deepseek-flash", "api_key_env": "DEEPSEEK_API_KEY" },
    "deepseek-pro":  { "base_url": "https://api.deepseek.com/v1", "model": "deepseek-v4-pro",  "api_key_env": "DEEPSEEK_API_KEY" }
  },
  "roles": {
    "default":      { "provider": "deepseek" },
    "repair":       { "provider": "deepseek", "reasoning": "low" },
    "escalate":     { "provider": "deepseek-pro", "reasoning": "high" },
    "orchestrator": { "provider": "deepseek-pro", "reasoning": "max" }
  },
  "agents": {
    "planner": { "prompt": "Plan, then return the next action.", "provider": "deepseek-pro", "reasoning": "high" }
  },
  "escalation": { "max_repairs": 2, "max_escalations": 1, "breaker_threshold": 3,
                  "ladder": ["repair", "escalate", "orchestrator"] },
  "proposer": { "provider": "deepseek", "model": "deepseek-flash" },
  "chat":     { "provider": "deepseek", "model": "deepseek-flash" }
}
```

- **providers** — connection + model defaults per alias; `api_key_env` keeps
  the key in env / `~/.nodecode/auth.json` instead of the file. `api_key` (and
  the value `api_key_env` points at) may start with `!` to run a token helper,
  e.g. `"api_key": "!op read op://private/deepseek --account my"` or
  `"!pass show DeepSeek/API"` — the command's first non-blank stdout line is the key.
- **roles** — the policy positions the loop routes through (dirge's
  `*_provider` keys, one map). A role may name an `agent` (its prompt becomes
  the role's system prompt) and may override `model` / `reasoning` /
  `temperature` per rung.
- **agents** — named agent profiles: `prompt`, `provider`, `reasoning`,
  `temperature`, `allow_tools` / `deny_tools`.
- **escalation** — the repair → escalate → **orchestrator** ladder. The
  orchestrator rung is the deep-thinker escalation: it fires only when you
  define a role/agent of that name and include it in `ladder`; when the rung's
  model/reasoning differs from the weaker roles, the loop routes there before
  circuit-breaking. `faraday config --check` flags a ladder that names an
  undefined role.
- **proposer** / **chat** — which provider/model those two harness parts use
  (the legacy `llm.*` settings shape still works for both).

## Tests

    cd harness && jolt run test/faraday/harness_test.clj
    cd harness && jolt run test/faraday/supervisor_test.clj
    cd harness && jolt run test/faraday/model_proposer_test.clj
    cd harness && jolt run test/faraday/config_test.clj

## Status

Implemented and tested: DB CRUD, prefix-nested scope hook resolution, the
must-be-able-to-fail registration rule, task-start reification, the honest
end-to-end run, and the self-improvement gate (observe -> propose -> soak/rollback
-> persist -> record). Turn-granular (pre before the loop, post after); per-cell
firing lands with the nREPL middleware spine.
