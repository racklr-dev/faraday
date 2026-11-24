# faraday settings — the configurable surface

`faraday` is configured through a `settings.json` shaped like dirge's
`config.json` — the same idea as `.claude/settings.json` / `.gemini/settings.json`.
Every key is optional; what you don't set falls through to the built-in defaults in
`harness/src/faraday/harness/config.clj`.

This file is the field-by-field reference. The authoritative resolver is
`faraday.harness.config` (`config.clj`): if this doc and the resolver disagree,
the resolver wins.

## Where settings live

Resolution precedence, highest first:

| layer | file | notes |
|---|---|---|
| CLI flags / opts | — | override everything |
| env | — | `FARADAY_LLM_BASE_URL`, `FARADAY_LLM_API_KEY`/`FARADAY_LLM_KEY`, `DEEPSEEK_API_KEY`, `FARADAY_LLM_MODEL` |
| project settings | `<project>/.faraday/settings.json` | git-ignored, sibling of `harness.db` |
| user settings | `~/.faraday/settings.json` | the file you usually edit |
| nodecode auth | `~/.nodecode/auth.json` | api keys only (`api_keys.<alias>.key`) |
| built-in defaults | `config.clj` `builtin-defaults` | lowest precedence |

The settings-file locations themselves are overridable via `FARADAY_SETTINGS`
(user settings path) and `FARADAY_NODECODE_AUTH` (auth path) — tests use these
to stay hermetic.

Keys are read with underscores normalized to hyphens, so `base_url` and
`base-url`, `api_key_env` and `api-key-env`, `max_repairs` and `max-repairs`
are the same key. Use whichever spelling you like.

## Inspecting the resolved surface

```sh
faraday config               # the whole resolved surface (secrets redacted)
faraday config --check       # validate references (ladder names defined roles, ...)
faraday config model-step    # the escalation ladder the agent loop will run
faraday config provider P    # one provider, resolved
faraday config role R        # one role, resolved
faraday config agent A       # one agent, resolved
```

`faraday config` prints the *resolved* surface — after merging all layers — so
it is the ground truth for what faraday actually sees.

## The sandbox and `sandbox.nix_flake` / `.#default`

The chat's tools run inside a jolt-sandbox microVM (a tiny alpine VM), not on
the host. `sandbox.nix_flake` names a Nix devShell to layer into that VM before
each tool runs, so tools see the same toolchain the repo expects.

```json
{ "sandbox": { "nix_flake": ".#default" } }
```

`.#default` is a Nix *flake installable*, resolved by `nix print-dev-env` from
the faraday repo root:

### `sandbox.egress` — allow-listed outbound hosts

By default the microVM has no outbound network. To give it managed egress to a
set of hosts, set `sandbox.egress`:

```json
{ "sandbox": { "egress": {
    "allowed_domains": ["github.com", "pypi.org", "files.pythonhosted.org",
                        "crates.io", "static.crates.io", "index.crates.io"],
    "allowed_ports": [80, 443],
    "upstream": "1.1.1.1" } } }
```

- `allowed_domains` — dnsmasq resolves only these domains (and their
  subdomains) and sinkholes everything else, so DNS itself is the domain gate.
- `allowed_ports` — tcp ports the guest may egress to (default `[80 443]`),
  enforced by an nftables default-deny forward chain.
- `allowed_cidrs` (optional) — extra ip ranges to allow, bypassing DNS.
- `upstream` — the resolver dnsmasq forwards allow-listed queries to
  (default `1.1.1.1`).

Needs `sudo` on the host (TAP + dnsmasq + nft + iptables), which faraday already
requires for the sandbox.

- `.` — the flake in the current directory (`faraday/flake.nix`).
- `#default` — the `default` attribute of the flake's `devShells.<system>` set.
  Because `nix print-dev-env` looks in `devShells`, `.#default` is the faraday
  devShell (jolt + ripgrep + git + openssl + openssh + clj-kondo + jq + native
  libs) — *not* `packages.<system>.default` (which also exists in this flake and
  is the jolt toolchain package).

Mechanically (in `jolt.microvm.nix/resolve!`): faraday runs
`nix print-dev-env <ref>`, caches the generated `dev-env.sh` plus the nix bash,
mounts the host `/nix/store` read-only into the VM, and wraps the tool exec so
`PATH` and env vars (including `LD_LIBRARY_PATH`) come from that devShell. The
nix store mount is why nix-built binaries run in the musl alpine guest without
a guest glibc.

- Any flake ref works: `github:owner/repo#devShell`, `nixpkgs#package`,
  `path:../other#default`. A relative ref like `.#default` resolves against the
  cwd (the repo root for faraday's chat).
- `nix` with flakes enabled must be on the host `PATH` (`nix print-dev-env`
  runs on the host).
- The resolved devShell is cached by `ref + system + flake-dir`; after you
  change `flake.nix`, clear the cache (or use a different ref).
- It only layers devShells: the value is passed to `nix print-dev-env`, which
  evaluates a devShell, not a package.

Two flat spellings also work for backward compatibility: `sandbox_nix_flake`
and `sandbox_apk_packages` (top-level) are merged into the `sandbox` object.

## Field reference

### `providers`

Named connection profiles: `alias -> {…}`. The alias is what `roles` and
`agents` refer to.

| key | type | meaning |
|---|---|---|
| `provider_type` | string | `openai-completions` (the wire the models speak) |
| `base_url` | string | API base URL, e.g. `https://api.deepseek.com/v1` |
| `model` | string | default model for this alias |
| `api_key_env` | string | env var that holds the key (preferred over inlining) |
| `api_key` | string | inline key (avoid); may start with `!` to run a token helper |
| `reasoning` | string | `low` / `medium` / `high` / `max` effort hint |
| `temperature` | number | sampling temperature |

Token helpers: a value starting with `!` is a shell command whose first
non-blank stdout line is the key, e.g. `!op read op://private/deepseek` or
`!pass show DeepSeek/API`. The result is memoized once per process.

### `roles`

Policy positions the agent loop routes through — dirge's seven `*_provider`
keys collapsed into one map. `role -> {…}`.

| key | type | meaning |
|---|---|---|
| `provider` | string | providers alias |
| `model` | string | override the alias's model for this role |
| `reasoning` | string | effort hint |
| `temperature` | number | sampling temperature |
| `agent` | string | name of an `agents` profile; its prompt becomes this role's system prompt |
| `system` | string | system prompt for this role |

Built-in roles: `default`, `repair`, `escalate`, `orchestrator`, `critic`,
`review`, `summarizer`, `subagent`, `approval`.

### `agents`

Named agent profiles. `agent -> {…}`.

| key | type | meaning |
|---|---|---|
| `prompt` / `system` | string | the agent's prompt |
| `provider` | string | providers alias |
| `model` | string | model override |
| `reasoning` | string | effort hint |
| `temperature` | number | sampling temperature |
| `allow_tools` | string[] | tools this agent may call |
| `deny_tools` | string[] | tools this agent may not call |
| `description` | string | free text (taskboard/listing) |

### `escalation`

The repair → escalate → orchestrator ladder that runs after the default role
fails.

| key | type | meaning |
|---|---|---|
| `max_repairs` | integer | repair attempts before escalating |
| `max_escalations` | integer | escalation rungs before the breaker |
| `breaker_threshold` | integer | consecutive failures that open the circuit |
| `ladder` | string[] | role positions tried in order, e.g. `[repair escalate orchestrator]` |

The `orchestrator` rung fires only if you define a role/agent of that name and
include it in `ladder`. `faraday config --check` flags a ladder naming an
undefined role.

### `mounts`

Global volume mounts for every sandbox exec — compose-style
`host:guest[:ro|rw]`, defaulting to `ro` (read-only). `~` expands to your
home.

```json
"mounts": [
  "~/.faraday:/home/sandbox/.faraday:rw",
  "~/.nodecode:/home/sandbox/.nodecode:ro",
  "~/projects:/home/sandbox/projects:rw"
]
```

The chat always adds `~/.faraday` and `~/.nodecode` read-only at the sandbox
user's home when they exist, so faraday inside the exec can read the same
settings/auth as the host without writing them.

### `sandbox`

The sandbox extension surface.

| key | type | meaning |
|---|---|---|
| `apk_packages` | string[] | extra alpine packages installed in the VM, e.g. `[python3]` |
| `nix_flake` | string | flake devShell layered in, e.g. `.#default` — see above |

### `proposer`

The self-improvement proposer (`faraday improve --proposer model`).

| key | type | meaning |
|---|---|---|
| `provider` | string | providers alias |
| `model` | string | model override |
| `base_url` | string | API base URL override |

### `chat`

The chat TUI's defaults.

| key | type | meaning |
|---|---|---|
| `provider` | string | providers alias |
| `model` | string | model override |
| `system` | string | system prompt |
| `wrap` | integer | wrap width |
| `context` | string \| string[] | standing context injected into every chat request |

### `context` (top-level)

Operator standing context, injected into every chat request — the editable
standing-context model. A string or an array of strings.

### `api_keys`

Inline keys: `api_keys.<alias>.key`. Prefer `api_key_env` or
`~/.nodecode/auth.json`; this exists for symmetry with the auth file.

### `llm` (legacy)

The pre-`proposer`/`chat` settings shape (`llm.base_url`, `llm.api_key`,
`llm.model`). Still honored for the proposer and chat as a fallback, but the
dedicated sections win.

## Secrets

- Never put a raw key in `settings.json`. Use `api_key_env` and keep the key in
  the environment or `~/.nodecode/auth.json` (`api_keys.deepseek.key`).
- `api_key` (and the value `api_key_env` points at) may start with `!` to run a
  token helper.
- `faraday config` redacts `api_key` values (`<set>` / `<unset>`), so it is safe
  to paste.

## Example and JSON Schema

`harness/settings.example.json` is the full annotated shape, with a `$schema`
key pointing at `docs/settings.schema.json` so editors that support JSON Schema
autocomplete/validate it in place.

`docs/settings.schema.json` describes the file *shape* for editor autocomplete
and validation. It is deliberately descriptive, not normative: the resolver in
`faraday.harness.config` is the source of truth, and the schema allows
additional properties so legacy (`llm`) and forward keys never fail validation.
It types the file, not the merged/resolved semantics — precedence, env
fallbacks, `!` token helpers, and underscore↔hyphen normalization are resolver
behaviour and cannot be expressed in a schema.
