# jolt-vm-pool — warm VM pool + snapshot hibernate

One concrete mechanism: a warm pool of microVMs for the jolt control plane.
It hands out sandboxes from `jolt-microvm` images resolved by `jolt-oci`,
reuses released ones, and hibernates idle sandboxes to snapshots that can be
restored later. It consumes the Sandbox Request contract
(jolt-contracts SPEC.md §2) and can take an optional admission hook (Budget
Permit seam, SPEC.md §5).

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-vm-pool
        {:git/url "https://github.com/racklr-dev/jolt-vm-pool"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.vm-pool :as pool])

(def p (pool/new-pool {:image "oci://ghcr.io/jolt-lang/distroless-base@sha256:..."
                       :cache-dir "/var/lib/jolt/vm-pool"
                       :max-warm 4}))
(def h (pool/acquire p {:v 1 :request-id "r1" :image "..." :argv ["/bin/echo" "ok"]}))
(pool/release p h)
(def snap (pool/hibernate p h))
(pool/restore p snap)
(pool/close! p)
```

## Status

Implemented. The pool keeps a bounded warm set of microVMs keyed by image,
reuses the most recently released warm VM on acquire, evicts the
least-recently-released past `:max-warm`, and hibernates/restores via
jolt-microvm snapshots. The unit test (`test/jolt/vm_pool_test.clj`) runs
with in-memory fakes for jolt-microvm, so it needs no KVM.

The pool owns VM lifecycle only. To run a request's argv, execute against the
handle's live VM: `(jolt.microvm/exec (:vm handle) argv ...)`.

Known MVP limits: request mount `readonly` is ignored (microvm mounts are
read/write); a released warm VM keeps its guest state (warm reuse); no
liveness probe on reuse.

## License

EPL-2.0
