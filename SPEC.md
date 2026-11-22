# jolt-plane data contracts — canonical wire spec

Version 1.0. This document is the single pin for every data shape that crosses a
component boundary in the jolt control plane. Each library owns its logic; this
file owns the bytes between libraries.

## Conventions

- **Wire encoding is JSON.** Every contract below is a JSON object. A Clojure
  implementation may hold the equivalent EDN/map in-process, but anything that
  crosses a process, pipe, socket, or FFI boundary MUST serialize to the shape
  shown here, with exactly these keys and no surprises.
- **Every envelope carries `"v": 1`.** Version first, always. A reader that
  sees an unknown `v` MUST reject, never guess.
- **IDs are opaque strings** (ULID, UUID, or `<prefix>_<random>`). Readers must
  not parse structure out of an id; they treat it as a byte-string key.
- **Unknown keys are ignored** by readers (forward compatibility); **missing
  required keys are an error**. Optional keys are marked `?` in the tables.
- **Timestamps are RFC 3339 UTC**, e.g. `"2026-11-22T07:24:19.907Z"`.
- **`null` vs absent**: absent means “not supplied”; `null` means “explicitly
  none”. Readers must preserve the distinction where the table calls for it.
- Canonical examples live in `examples/` and are the test vectors every
  library's tests consume.

## Contract map

| contract | produced by | consumed by | MVP? |
|---|---|---|---|
| 1. Thread Message | jolt-fanout | jolt-ledger, jolt-fanout, jolt-rap | yes |
| 2. Sandbox Request | jolt-fanout | jolt-vm-pool | yes |
| 3. Tool Invocation | jolt-rap | jolt-fanout, jolt-rap | yes |
| 4. Egress Contract | operator/config | jolt-vm-pool, jolt-tapmasq | yes |
| 5. Budget Permit | jolt-admission | jolt-vm-pool, jolt-fanout | deferred |
| 6. Policy Set | operator/config | jolt-cedar | deferred |

Deferred contracts are the optional security seams: they are pinned now so the
MVP libraries can accept them as hooks later, but nothing in the MVP path is
blocked on them.

---

## 1. Thread Message

The unit of state and control flow. jolt-fanout emits these; jolt-ledger stores
them, assigns the sequence number, and dedups on `msg-id`. One logical thread is
one tree of messages rooted at a `thread-id`.

```json
{
  "v": 1,
  "thread-id": "thr_01J8VK2Q",
  "parent-id": null,
  "msg-id": "msg_01J8VK2Q",
  "seq": 0,
  "kind": "spawn",
  "ts": "2026-11-22T07:24:19.907Z",
  "payload": {}
}
```

| field | type | req | meaning |
|---|---|---|---|
| `v` | int | yes | always 1 |
| `thread-id` | string | yes | root thread this message belongs to |
| `parent-id` | string? | yes (may be null) | parent thread, null at the root |
| `msg-id` | string | yes | global idempotency key; ledger rejects a repeat |
| `seq` | int | yes | per-`thread-id` monotonic sequence, assigned by the ledger |
| `kind` | string | yes | one of `spawn` `inherit` `report` `yield` `user-input` `system` |
| `ts` | string | yes | RFC 3339 UTC |
| `payload` | object | yes | kind-specific, see below |

Invariants: `seq` strictly increases per `thread-id`; `msg-id` is unique across
the whole ledger (redelivery of the same `msg-id` is an idempotent no-op, not an
error); `parent-id` never equals `thread-id`.

Kind payloads (all objects; a reader of an unknown `kind` rejects):

- `spawn`: `{ "spec": <SandboxRequest or tool spec>, "memo-key": string? }`
- `inherit`: `{ "from-thread-id": string, "snapshot-ref": string }`
- `report`: `{ "status": "ok|error|cancelled", "result": object?,
  "segments": [ <DisplaySegment> ]? }`
- `yield`: `{ "state-hash": string, "snapshot-ref": string }` — hibernate-to-data
- `user-input`: `{ "text": string }`
- `system`: `{ "event": string, "data": object? }`

`DisplaySegment` (shared with Tool Invocation):
`{ "type": "text|file|json", "content": string|object,
  "path": string?, "mime": string? }` — clients use the first type they support.

---

## 2. Sandbox Request

Boot-and-run spec handed to jolt-vm-pool. Immutable once issued; a request id
must not be reissued. The pool returns a sandbox handle, not the guest output
(streamed output follows the Tool Invocation contract via jolt-rap).

```json
{
  "v": 1,
  "request-id": "req_01J8VK2Q",
  "image": "oci://ghcr.io/jolt-lang/distroless-base@sha256:abc...",
  "argv": ["/bin/sh", "-c", "echo ok"],
  "env": { "PATH": "/usr/bin" },
  "mounts": [ { "host-path": "/data", "tag": "data", "readonly": false } ],
  "cpu": 1,
  "mem-mb": 512,
  "timeout-ms": 30000,
  "egress": { "ref": "egr_01J8VK2Q" },
  "stdin": null
}
```

| field | type | req | meaning |
|---|---|---|---|
| `v` | int | yes | always 1 |
| `request-id` | string | yes | idempotency key for this boot |
| `image` | string | yes | OCI ref or local path; `@sha256:` digest preferred |
| `argv` | [string] | yes | `argv[0]` is the program; no shell wrapping |
| `env` | object | ? | environment passed to the guest |
| `mounts` | [object] | ? | virtiofs mounts: `host-path`, `tag`, `readonly` |
| `cpu` | int | ? | vCPU count (default 1) |
| `mem-mb` | int | ? | memory limit in MiB |
| `timeout-ms` | int | ? | hard kill after this long; absent = pool default |
| `egress` | object | ? | `{ "ref": id }` or an inline Egress Contract |
| `stdin` | string? | ? | full stdin; `null`/absent = closed |

Invariants: `argv` is non-empty; `image` must pin a digest for reproducible
snapshots; a `mounts[].tag` must be a valid virtiofs tag and unique within the
request.

---

## 3. Tool Invocation

The RAP/MCP tool call, streamed. The `call-id` ties the request to its stream of
results. Exactly one terminal result per `call-id`.

```json
{
  "v": 1,
  "call-id": "call_01J8VK2Q",
  "thread-id": "thr_01J8VK2Q",
  "name": "run_shell",
  "args": { "argv": ["ls", "-la"] },
  "display-as": "ls -la"
}
```

| field | type | req | meaning |
|---|---|---|---|
| `v` | int | yes | always 1 |
| `call-id` | string | yes | idempotency key for this invocation |
| `thread-id` | string | yes | owning thread |
| `name` | string | yes | tool name, no whitespace |
| `args` | object | yes | tool arguments (tool-defined schema) |
| `display-as` | string | ? | human summary shown by UIs |

Terminal result (one per `call-id`, sent by the tool server when the call
finishes, is cancelled, or errors):

```json
{
  "v": 1,
  "call-id": "call_01J8VK2Q",
  "status": "ok",
  "segments": [ { "type": "text", "content": "total 4\n" } ],
  "exit-code": 0,
  "error": null
}
```

| field | type | req | meaning |
|---|---|---|---|
| `status` | string | yes | `ok` `error` `cancelled` |
| `segments` | [DisplaySegment] | yes | may be empty |
| `exit-code` | int? | ? | process exit code when applicable |
| `error` | object? | ? | `{ "message": string, "code": string? }` on error |

Control messages share this envelope: `invoke`, `cancel`, `close` carry
`call-id` (+ `thread-id` for `close`) and no `args`; `subscribe` carries a
`subscription-id` and no `args`. Streaming is intermediate `segments` frames,
not a terminal result.

---

## 4. Egress Contract

What a guest is allowed to reach. This is the proven tapmasq shape: DHCP and the
host gateway are always permitted, DNS is fixed at the named upstream, and
outbound traffic is allow/deny-listed by CIDR with a default deny. Inline or
referenced by `id` from a Sandbox Request.

```json
{
  "v": 1,
  "egress-id": "egr_01J8VK2Q",
  "dns": ["1.1.1.1"],
  "allow-cidrs": ["1.1.1.1/32"],
  "deny-cidrs": ["8.8.8.8/32"],
  "host-gateway": true,
  "default": "deny"
}
```

| field | type | req | meaning |
|---|---|---|---|
| `v` | int | yes | always 1 |
| `egress-id` | string | yes | identity of this policy |
| `dns` | [string] | yes | upstream DNS servers the guest may query |
| `allow-cidrs` | [string] | yes | outbound CIDRs permitted (NAT masquerade) |
| `deny-cidrs` | [string] | ? | outbound CIDRs dropped, wins over allow |
| `host-gateway` | bool | yes | allow the TAP host gateway (`192.168.44.1`) |
| `default` | string | yes | `deny` (only `deny` in v1) |

Invariants: DHCP is always permitted and never listed; `deny-cidrs` takes
precedence over `allow-cidrs`; `default` is `deny`, so an empty `allow-cidrs`
means outbound-only-DNS-and-host.

---

## 5. Budget Permit — DEFERRED (jolt-admission)

Optional admission seam. Not required for MVP; pinned now so vm-pool and fanout
can accept an admission hook later without a contract change.

```json
{
  "v": 1,
  "permit-id": "perm_01J8VK2Q",
  "owner": "thr_01J8VK2Q",
  "cost": { "cpu": 1, "mem-mb": 512 },
  "priority": 0,
  "timeout-ms": 30000,
  "status": "granted"
}
```

| field | type | req | meaning |
|---|---|---|---|
| `permit-id` | string | yes | idempotency key |
| `owner` | string | yes | thread holding the permit |
| `cost` | object | yes | `{ "cpu": int, "mem-mb": int }` |
| `priority` | int | ? | higher wins under contention (default 0) |
| `timeout-ms` | int | ? | `0` = block forever; absent = default |
| `status` | string | yes | `granted` `queued` `denied` `released` |

---

## 6. Policy Set — DEFERRED (jolt-cedar)

Optional authorization seam. Maps one-to-one onto `cedar_policy::ffi`
`*_json_str` entry points: `is_authorized_json_str`, `validate_json_str`,
`preparse_policy_set`. Not required for MVP.

```json
{
  "v": 1,
  "policies": ["permit(principal, action, resource) when { true };"],
  "schema": "{}",
  "entities": [],
  "context": {}
}
```

| field | type | req | meaning |
|---|---|---|---|
| `policies` | [string] | yes | Cedar policy source texts |
| `schema` | string | ? | Cedar schema JSON |
| `entities` | [object] | ? | Cedar entity JSON array |
| `context` | object | ? | Cedar request context |

---

## Change rules

- Add a key: bump nothing, document it here first, readers ignore it until they
  learn it.
- Change the meaning of a key: new contract version, never in place.
- Remove a required key: new contract version.
- Every change lands in this file and its `examples/` fixture in the same commit.
