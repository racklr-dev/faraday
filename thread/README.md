# faraday.thread — thread-keyed sandbox + repo store

Infinity's "a ThreadId keys an agent's sandbox and repo state", over the
faraday pool. A Store holds, per thread-id, a live sandbox handle (or its
hibernate snapshot-ref) plus a host worktree mounted into the sandbox.

```clojure
(require '[faraday.thread :as thread] '[faraday.rap :as rap])

(def store (thread/new-store {:pool pool :repo-root "/srv/jolt-threads"}))

;; wire it as the rap run-hook seam: every invocation runs in its owning
;; thread's sandbox, with the repo mounted at /mnt/repo, and stays warm.
(def srv (rap/new-server {:fanout rt :run-hook (thread/rap-hook store)}))
```

## API

| fn | role |
|---|---|
| `new-store` | open over `:pool` + `:repo-root` (optional `:repo-fn`) |
| `exec!` | run a request's argv in the thread's sandbox (repo as cwd); leaves it warm |
| `yield!` | hibernate the thread's sandbox to a snapshot-ref |
| `resume!` | restore the thread's snapshot-ref into a live sandbox |
| `close-thread!` | release the thread's sandbox and forget its state |
| `rap-hook` | the `faraday.rap` `:run-hook` seam |
| `stats` | `{:threads :live :hibernated}` |

Repo state is a `repo-state` contract (`:mode` `:git`/`:jj`/`:direct`). The
default `:repo-fn` resolves `:direct` to its `:host-path`, and clones
`:git`/`:jj` into `repo-root/<thread-id>`. First use wins per thread; later
calls reuse the same worktree, so repo state persists across a thread's tool
calls exactly like its sandbox.

Status: implemented + unit-tested with fake pool/microvm (no KVM).
