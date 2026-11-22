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

## Status

Scaffold — the public API is pinned and tested, bodies throw `:not-implemented`.
The server implementation lands in the next card. Note: deps.edn currently
pins jolt-fanout and jolt-vm-pool via `:local/root` so the scaffold tests run
before push; swap both to pinned `:git/sha` values before publishing.

## License

EPL-2.0
