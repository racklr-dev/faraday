# jolt-rap — RAP tool server + MCP adapter

One concrete mechanism: the RAP tool server and MCP adapter for the jolt
control plane. It runs Tool Invocations (jolt-contracts SPEC.md §3) against a
jolt-vm-pool, streams results to subscribers, and adapts them to MCP. Each
invocation has exactly one terminal result, keyed by `call-id`.

## Use

```clojure
;; deps.edn — published form (after fanout/vm-pool are pushed)
{:deps {racklr-dev/jolt-rap
        {:git/url "https://github.com/racklr-dev/jolt-rap"
         :git/sha "<pinned sha>"}}}
```

```clojure
(require '[jolt.rap :as rap])

(def srv (rap/new-server {:vm-pool pool :fanout rt}))
(rap/subscribe srv call-id (fn [frame] (println frame)))
(rap/invoke srv {:v 1 :thread-id "thr_1" :name "run_shell" :args {:argv ["ls"]}})
(rap/close! srv)
```

## Two server modes

`new-server` takes either a `:vm-pool` (the server owns acquire/exec/release) or
a `:run-hook` (the caller owns the side effects — used by jolt-plane to run
invocations through the gate).

```clojure
;; mode 1 — :vm-pool (server acquires a sandbox and runs argv itself)
(def srv (rap/new-server {:vm-pool pool :fanout rt}))

;; mode 2 — :run-hook (delegate to the plane runner, which gates + spawns + reports)
(require '[jolt.plane :as plane])
(def srv (rap/new-server {:fanout rt
                          :run-hook (fn [request]
                                      ;; request = Sandbox Request; return
                                      ;; {:status :ok|:error|:denied :exit int? :stdout :stderr :error}
                                      (plane/run-sandbox! runner request))}))
```

With `:run-hook` the server no longer acquires/releases the vm-pool and no
longer emits the report Thread Message — the hook owns those side effects; the
server maps the hook's result to the RAP terminal frame. `:denied` becomes an
error terminal with error code `"denied"`; `:error` takes its code from the
`:error` map or `(name :type)`; `:ok` with exit 0 is `"ok"`. See
`hook-result->frame` in `src/jolt/rap.clj`.

## Status

Implemented. The server runs Tool Invocations (SPEC.md §3) asynchronously
against the vm-pool: `invoke` builds a Sandbox Request from the call args,
acquires a sandbox, runs the shell-quoted argv, releases the sandbox, emits a
report Thread Message via fanout, and delivers exactly one terminal result.
`subscribe`/`on-result` work before or after `invoke` (pending subscriptions
attach on invoke; late ones replay the stored terminal). `cancel`/`close`
short-circuit in-flight calls with a terminal cancelled frame; `close!` releases
a subscription or stops the server. The test fakes only vm-pool/microvm and runs
against a real ledger/fanout — `test/jolt/rap_test.clj`. The integration
test (`test/jolt/rap_integration.clj`) drives a real KVM microVM through a
real vm-pool and asserts the async terminal result lands via `on-result`.

deps.edn pins jolt-fanout and jolt-vm-pool to their published `:git/sha` values
on `https://github.com/racklr-dev`.

## Running the tests

Unit test (fakes vm-pool/microvm, real ledger/fanout):

    cd jolt-rap && JOLT_SKIP_MIN_VERSION=1 JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/rap_test.clj

Integration test (real KVM, async result over rap -> vm-pool -> microvm):

    cd jolt-rap && sudo -u jm --preserve-env env JOLT_BIN=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt PATH=/tmp/jolt-rel/jolt-v0.8.28-x86_64-linux:$PATH /tmp/jolt-rel/jolt-v0.8.28-x86_64-linux/jolt run test/jolt/rap_integration.clj

Prerequisites: /dev/kvm writable (jm in the kvm group) and a jolt binary at
JOLT_BIN that satisfies the 0.8.28 floor. Never run under `sg kvm`: it makes
kvm the primary gid and libkrun virtio-fs guest writes fail EPERM.

## License

EPL-2.0
