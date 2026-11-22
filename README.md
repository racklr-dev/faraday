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

Scaffold — the public API is pinned and tested, bodies throw `:not-implemented`.
The pool implementation lands in the next card.

## License

EPL-2.0
