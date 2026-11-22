# jolt-plane — control-plane daemon for the jolt plane

One concrete mechanism: the plane daemon that ties the two security seams
into one gate. A sandbox run is authorized by the Cedar policy engine
(jolt-cedar, jolt-contracts SPEC §6) and only then admitted under a resource
budget (jolt-admission, SPEC §5). `gate` is that gate; the CLI in
`src/jolt/plane/main.clj` boots it.

Standalone: depends only on jolt-admission and jolt-cedar. The daemon
skeleton (budget + policy wired end to end) is here; the N-sandbox fanout
loop and the RAP/MCP transport land in nc-FF99 / nc-916C.

## Use

```clojure
;; deps.edn
{:deps {racklr-dev/jolt-plane
        {:git/url "https://github.com/racklr-dev/jolt-plane"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.plane :as plane])

(def p (plane/new-plane
        {:budget {:cpu 4 :mem-mb 4096}
         :policy-set {:policies ["permit(principal, action, resource);"]
                      :schema {} :entities []}}))

(def r (plane/gate p {:request-id "req_1"
                      :principal {:type "Agent" :id "alice"}
                      :action {:type "Action" :id "exec"}
                      :resource {:type "Image" :id "oci://example@sha256:abc"}
                      :context {} :owner "thr_1" :cost {:cpu 1 :mem-mb 512}}))
;; => {:decision :allow :permit {:v 1 :status "granted" ...}}
(plane/release p (:permit r))
(plane/close! p)
```

For an identity-scoped policy, the Cedar source is e.g.
`permit(principal == Agent::"alice", action, resource);` — in Clojure write it
as `(str "permit(principal == Agent::" (char 34) "alice" (char 34) ", action, resource);")`
or escape the quotes.

## CLI

```sh
# validate config + policy at boot
jolt run src/jolt/plane/main.clj plan --config examples/plane-config.json
# run one gate
jolt run src/jolt/plane/main.clj check --config examples/plane-config.json --request examples/run-request.json
# skeleton serve (accept loop lands in nc-916C)
jolt run src/jolt/plane/main.clj serve --config examples/plane-config.json
```

jolt-cedar's native library is gitignored, so set `JOLT_CEDAR_LIB` to a built
`libcedar_policy.so` before any command that opens a plane.

## Status

Scaffold (nc-2355): budget + policy wired end to end, CLI + smoke test green.
Next: nc-FF99 (N sandboxes under budget with policy egress), nc-916C (E2E
harness over RAP/MCP).

## License

EPL-2.0 (wrapper); cedar-policy is Apache-2.0.
