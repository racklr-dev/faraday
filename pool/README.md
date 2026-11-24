# faraday-pool — warm VM pool + snapshot hibernate

A warm pool of microVMs for a jolt-based control plane: it hands out sandboxes
from [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) images resolved
by [jolt-oci](https://github.com/racklr-dev/jolt-oci), reuses released ones
(LRU), and hibernates idle sandboxes to snapshots that can be restored later.
It consumes the Sandbox Request contract
([faraday-contracts](../contracts/SPEC.md) §2) and can take an optional
admission hook (Budget Permit seam, §5).

## Requirements

- [jolt](https://github.com/jolt-lang/jolt) 0.8.28+
- For the integration test: libkrun + libkrunfw, a writable `/dev/kvm`
  (user in the `kvm` group), and `ssh`/`ssh-keygen` — see jolt-microvm

## Build

Nothing to compile. Run the suites:

```sh
# unit: no KVM (jolt-microvm is faked)
cd pool && jolt run test/faraday/vm_pool_test.clj

# integration: boots a real alpine:3.20 KVM microVM (needs the requirements above)
cd pool && jolt run test/faraday/vm_pool_integration.clj
```

Never run under `sg kvm`: it makes kvm the primary gid and libkrun virtio-fs
guest writes fail EPERM.

## Usage

```clojure
{:deps {racklr-dev/faraday-pool
        {:git/url "https://github.com/racklr-dev/faraday"
         :git/sha "<pinned sha>"
         :deps/root "pool"}}}
```

```clojure
(require '[faraday.pool :as pool])

(def p (pool/new-pool {:image "oci://ghcr.io/jolt-lang/distroless-base@sha256:..."
                       :cache-dir "/var/lib/jolt/vm-pool"
                       :max-warm 4}))
(def h (pool/acquire p {:v 1 :request-id "r1" :image "..." :argv ["/bin/echo" "ok"]}))
(pool/release p h)
(def snap (pool/hibernate p h))
(pool/restore p snap)
(pool/stats p)   ; => {:warm n :total n :snapshots n}
(pool/close! p)
```

## API

| Function | Args | Returns | Errors |
|---|---|---|---|
| `new-pool` | `opts` | pool handle | throws on a bad `:image`/`:cache-dir`/`:max-warm` |
| `acquire` | `pool request` | sandbox handle | throws `:admission-denied` from the hook; blocks when full |
| `release` | `pool handle` | new warm count | throws on a handle not held |
| `hibernate` | `pool handle` | snapshot-ref string | throws on a handle not held |
| `restore` | `pool snapshot-ref` | restored handle | throws on an unknown snapshot-ref |
| `stats` | `pool` | `{:warm :total :snapshots}` | — |
| `close!` | `pool` | `:closed` | — |

`acquire` reuses the most recently released warm VM for the image, boots one
while fewer than `:max-warm` are live, and blocks until a release when full;
`release` returns the handle to the warm set and evicts the least-recently-
released past `:max-warm`. The pool owns VM lifecycle only — to run a
request's argv, execute against the handle's live VM
(`(jolt.microvm/exec (:vm handle) ...)`).

Known MVP limits: request mount `readonly` is ignored (mounts are read/write);
a released warm VM keeps its guest state; no liveness probe on reuse.

## Status

beta — the unit test runs with in-memory fakes (no KVM); the integration test
boots a real alpine KVM microVM and exercises warm reuse + snapshot/restore.

## License

EPL-2.0

## Learn more

- [jolt-lang](https://github.com/jolt-lang/jolt) — the language and toolchain
- [jolt-microvm](https://github.com/racklr-dev/jolt-microvm) — the VM lifecycle this pools
- [jolt-oci](https://github.com/racklr-dev/jolt-oci) — the image pull + unpack layer
- [faraday-contracts](../contracts/SPEC.md) — SPEC.md §2, the Sandbox Request shape
