# jolt-plane — control-plane daemon for the jolt plane

One concrete mechanism: the plane daemon that ties the two security seams
into one gate. A sandbox run is authorized by the Cedar policy engine
(jolt-cedar, jolt-contracts SPEC §6) and only then admitted under a resource
budget (jolt-admission, SPEC §5). `gate` is that gate; `new-runner`/`run-sandbox!`
wire that gate together with a warm VM pool (jolt-vm-pool) and a fanout runtime
(jolt-fanout) into the N-sandbox execution loop; the CLI in
`src/jolt/plane/main.clj` boots it.

Layers: `gate` (plane, depends on jolt-admission + jolt-cedar only) and the
runner layer (depends additionally on jolt-vm-pool + jolt-fanout). The runner
layer (N sandboxes under budget with policy egress) and the RAP/MCP transport
(nc-916C: a RAP server drives N tool invocations through the runner) are here.

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

### Runner layer (nc-FF99)

```clojure
(require '[jolt.plane :as plane]
         '[jolt.vm-pool :as pool]
         '[jolt.fanout :as fanout])

(def p (plane/new-plane {:budget {:cpu 2 :mem-mb 2048}
                         :policy-set {:policies [...] :schema {} :entities []}}))
(def pl (pool/new-pool {:image "oci://example@sha256:abc" :max-live 2}))
(def rt (fanout/new-runtime {}))

(def runner (plane/new-runner {:plane p :pool pl :fanout rt
                               :egress {:public {:allow-cidrs ["0.0.0.0/0"]}}}))

;; one sandbox: gate -> spawn -> acquire -> exec -> release -> report
(plane/run-sandbox! runner
  {:request-id "req_1" :owner "thr_1"
   :image "oci://example@sha256:abc"
   :argv ["/bin/echo" "hello"]
   :egress {:ref :public}
   :cpu 1 :mem-mb 512 :timeout-ms 30000})
;; => {:status :ok :request-id "req_1" :stdout "hello\n" ...}
;; deny/error surface as {:status :denied|:error} — run-sandbox! never throws.

;; run a batch, one result per request
(plane/run-all! runner [req1 req2 req3])

(plane/close-runner! runner)
```

A request's `:egress` is an inline Egress Contract map or `{:ref id}` resolved
against the runner's `:egress` registry; it is snake-cased (allow-cidrs →
allow_cidrs) and attached to the per-request Sandbox entity, because Cedar
reads resource attributes from the entities array, not the request.

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

## Tests

```sh
# no KVM: with-redefs microvm start!/stop!, injected exec-hook, real pool+ledger+fanout
cd ~/projects/jolt/jolt-plane && JOLT_SKIP_MIN_VERSION=1 \
  JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt \
  JOLT_CEDAR_LIB=/home/jm/projects/jolt/jolt-cedar/native/target/release/libcedar_policy.so \
  /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/plane_wiring_test.clj

# E2E: a RAP server drives N sandboxes through the gate (no KVM)
  /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/plane_e2e_test.clj
```

## Status

Scaffold (nc-2355): budget + policy wired end to end, CLI + smoke test green.
Runner (nc-FF99): gate + vm-pool + fanout wired, 6 sandboxes under a cpu2
budget with policy egress — 14/14 green. E2E (nc-916C): RAP server wired to
the runner via :run-hook, 6 concurrent tool invocations driven through the
gate — 10/10 green, and the repo is pushed to racklr-dev/jolt-plane.

## License

EPL-2.0 (wrapper); cedar-policy is Apache-2.0.
