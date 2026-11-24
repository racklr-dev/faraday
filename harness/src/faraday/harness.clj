;; faraday.harness — the harness DB: taskboard, scope tree, pre/post hooks,
;; and the honesty ledger (hook_runs). SQLite via jolt-lang/db (db.sqlite), the
;; same storage conventions as faraday-ledger: one connection, per-store lock,
;; idempotent CREATE TABLE IF NOT EXISTS. Every row is data the loop reads back
;; as loop-as-data; the honest-turn invariant lives here: a turn reports green
;; only if every expected hook ran and every blocking hook passed.
(ns faraday.harness
  "Harness DB: projects, scopes, hooks, hook_runs, tasks (SCHEMA.md).

  One DB per project root at .faraday/harness.db. Scopes are a folder tree
  (rel_path prefix nesting); hooks are pre/post middleware insertions reified
  as data; hook_runs is the honesty ledger; tasks is the kanban.
  resolve-hooks turns a scope into the loop-as-data :hooks map; task-start
  reifies a task into its full loop-as-data description."
  (:require [db.sqlite :as sql]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn- fail! [msg data]
  (throw (ex-info msg (assoc data :faraday.harness/error true))))

(defn- now [] (str (java.time.Instant/now)))

(defn sha-hex
  "Lowercase hex SHA-256 of s (a string)."
  [s]
  (let [md (java.security.MessageDigest/getInstance "SHA-256")
        bs (.digest md (.getBytes (str s) "UTF-8"))]
    (apply str (map (fn [b] (format "%02x" (bit-and (int b) 0xff))) bs))))

(defn- short-id [prefix s]
  (str prefix (subs (sha-hex s) 0 12)))

(defn- non-empty-string! [v field]
  (when-not (and (string? v) (seq (str/trim v)))
    (fail! (str (name field) " must be a non-empty string") {:field field :value v}))
  v)

(defn- ensure-parent-dir! [path]
  (let [f (java.io.File. path)]
    (when-let [p (.getParentFile f)] (.mkdirs p)))
  path)

(defn- parse-edn [s default]
  (if (string? s)
    (try (edn/read-string s) (catch Exception _ default))
    default))

(def ^:private cells #{"orient" "step" "invoke" "judge" "finalize" "*"})
(def ^:private positions #{"pre" "post"})
(def ^:private kinds #{"check" "lint" "slop-cop" "fast-test" "gate" "contract" "soak"})
(def ^:private gates #{"advisory" "blocking" "fail-open"})
(def ^:private how-keys #{:command :middleware :predicate :contract})

(declare project scope hook task)

;; -- open --------------------------------------------------------------------

(defn new-harness
  "Open (or create) the harness DB at opts :path. Returns an opaque handle."
  [opts]
  (let [path (or (:path opts) (fail! "new-harness requires a :path" {:opts opts}))
        _ (ensure-parent-dir! path)
        conn (sql/open path)]
    (try
      (doseq [q [(str "create table if not exists projects ("
                      "id text primary key,"
                      "root_path text not null unique,"
                      "name text not null,"
                      "default_manifest text not null,"
                      "created_at text not null,"
                      "updated_at text not null)")
                 (str "create table if not exists scopes ("
                      "id text primary key,"
                      "project_id text not null references projects(id),"
                      "rel_path text not null,"
                      "is_root integer not null default 0,"
                      "meta text not null default '{}',"
                      "created_at text not null,"
                      "updated_at text not null,"
                      "unique (project_id, rel_path))")
                 (str "create index if not exists scopes_project on scopes(project_id, rel_path)")
                 (str "create table if not exists hooks ("
                      "id text primary key,"
                      "project_id text not null references projects(id),"
                      "scope_id text not null references scopes(id),"
                      "cell text not null,"
                      "position text not null,"
                      "kind text not null,"
                      "how text not null,"
                      "why text not null,"
                      "gate text not null default 'advisory',"
                      "priority integer not null default 0,"
                      "enabled integer not null default 1,"
                      "timeout_ms integer,"
                      "created_at text not null,"
                      "updated_at text not null)")
                 (str "create index if not exists hooks_scope on hooks(scope_id, cell, position, priority)")
                 (str "create table if not exists hook_runs ("
                      "id text primary key,"
                      "hook_id text not null references hooks(id),"
                      "task_id text,"
                      "turn_id text not null,"
                      "cell text not null,"
                      "position text not null,"
                      "verdict text not null,"
                      "exit_code integer,"
                      "output_sha text,"
                      "ms integer not null,"
                      "ran_at text not null)")
                 (str "create index if not exists hook_runs_turn on hook_runs(turn_id, hook_id)")
                 (str "create table if not exists tasks ("
                      "id text primary key,"
                      "project_id text not null references projects(id),"
                      "scope_id text,"
                      "title text not null,"
                      "body text not null default '',"
                      "status text not null default 'open',"
                      "priority text not null default 'normal',"
                      "epic_id text,"
                      "manifest text,"
                      "assignee text,"
                      "dedup_key text,"
                      "created_at text not null,"
                      "updated_at text not null,"
                      "closed_at text)")
                 (str "create index if not exists tasks_scope on tasks(scope_id, status, priority)")
                 (str "create table if not exists supervisor_runs ("
                      "id text primary key,"
                      "task_id text not null,"
                      "observed_status text,"
                      "baseline_passed integer not null default 0,"
                      "proposal text,"
                      "candidate_key text,"
                      "verdict text not null,"
                      "soak_runs integer,"
                      "pointer text,"
                      "promoted_key text,"
                      "created_at text not null)")
                 (str "create index if not exists supervisor_runs_task on supervisor_runs(task_id, created_at)")]]
        (sql/query conn q []))
      {:conn conn :path path :closed (atom false) :lock (Object.)}
      (catch Throwable t
        (try (sql/close conn) (catch Throwable _))
        (throw t)))))

;; -- projects ----------------------------------------------------------------

(defn project-add!
  "Upsert a project by :root-path. Idempotent: re-running updates name and
  default_manifest in place. Returns the project map."
  [h {:keys [root-path name default-manifest id]}]
  (non-empty-string! root-path :root-path)
  (non-empty-string! name :name)
  (non-empty-string! default-manifest :default-manifest)
  (locking (:lock h)
    (let [conn (:conn h)
          ts (now)
          existing (first (sql/query conn "select id from projects where root_path = ?" [root-path]))]
      (if existing
        (do (sql/query conn "update projects set name = ?, default_manifest = ?, updated_at = ? where root_path = ?"
                       [name default-manifest ts root-path])
            (project h (:id existing)))
        (let [id (or id (short-id "prj-" root-path))]
          (sql/query conn
                     (str "insert into projects (id, root_path, name, default_manifest, created_at, updated_at) "
                          "values (?, ?, ?, ?, ?, ?)")
                     [id root-path name default-manifest ts ts])
          (project h id))))))

(defn project
  "The project by id."
  [h project-id]
  (locking (:lock h)
    (first (sql/query (:conn h) "select * from projects where id = ?" [project-id]))))

(defn project-by-root
  [h root-path]
  (locking (:lock h)
    (first (sql/query (:conn h) "select * from projects where root_path = ?" [root-path]))))

;; -- scopes ------------------------------------------------------------------

(defn- normalize-rel-path [s]
  (-> (or s "")
      str/trim
      (str/replace #"^/+|/+$" "")
      (str/replace #"\\+" "/")))

(defn scope-add!
  [h {:keys [project-id rel-path meta is-root]}]
  (non-empty-string! project-id :project-id)
  (let [rp (normalize-rel-path rel-path)
        id (short-id "scope-" (str project-id ":" rp))
        ts (now)
        meta-text (pr-str (or meta {}))]
    (locking (:lock h)
      (let [conn (:conn h)
            existing (first (sql/query conn "select id from scopes where project_id = ? and rel_path = ?" [project-id rp]))]
        (if existing
          (do (sql/query conn "update scopes set meta = ?, is_root = ?, updated_at = ? where id = ?"
                         [meta-text (if is-root 1 0) ts (:id existing)])
              (scope h (:id existing)))
          (do (sql/query conn
                         (str "insert into scopes (id, project_id, rel_path, is_root, meta, created_at, updated_at) "
                              "values (?, ?, ?, ?, ?, ?, ?)")
                         [id project-id rp (if is-root 1 0) meta-text ts ts])
              (scope h id)))))))

(defn scope
  [h scope-id]
  (locking (:lock h)
    (when-let [row (first (sql/query (:conn h) "select * from scopes where id = ?" [scope-id]))]
      (update row :meta #(parse-edn % {})))))

(defn scope-by-rel
  [h project-id rel-path]
  (locking (:lock h)
    (when-let [row (first (sql/query (:conn h) "select * from scopes where project_id = ? and rel_path = ?"
                                     [project-id (normalize-rel-path rel-path)]))]
      (update row :meta #(parse-edn % {})))))

(defn scope-list
  [h project-id]
  (locking (:lock h)
    (mapv #(update % :meta (fn [m] (parse-edn m {})))
          (sql/query (:conn h) "select * from scopes where project_id = ? order by rel_path" [project-id]))))

(defn scope-set-meta!
  [h scope-id meta]
  (locking (:lock h)
    (sql/query (:conn h) "update scopes set meta = ?, updated_at = ? where id = ?"
               [(pr-str (or meta {})) (now) scope-id])
    (scope h scope-id)))

;; -- out-of-process command running ------------------------------------------

(defn run-command
  "Run argv out-of-process; returns {:exit int|nil :out string :timeout? bool}.
  stderr is merged into :out. Shared by the must-be-able-to-fail proof at
  registration and by the CLI harness when it fires a command hook."
  [argv timeout-ms]
  (let [pb (doto (ProcessBuilder. ^java.util.List (java.util.ArrayList. (map str argv)))
             (.redirectErrorStream true))
        p (.start pb)
        reader (future (slurp (.getInputStream p)))
        exited? (.waitFor p (long (or timeout-ms 8000)) java.util.concurrent.TimeUnit/MILLISECONDS)]
    (if exited?
      {:exit (.exitValue p) :out (deref reader 8000 "") :timeout? false}
      (do (.destroyForcibly p)
          {:exit nil :out (deref reader 2000 "") :timeout? true}))))

;; -- hooks -------------------------------------------------------------------

(defn- valid-fixture?
  "A fixture proof spec: {:argv vector :good string :bad string}, or nil when
  the hook is not proven at registration."
  [fx]
  (or (nil? fx)
      (and (map? fx)
           (vector? (:argv fx)) (seq (:argv fx)) (every? string? (:argv fx))
           (string? (:good fx)) (string? (:bad fx)))))

(defn- valid-how?
  [how]
  (and (map? how)
       (= 1 (count (filter #(contains? how %) how-keys)))
       (let [k (first (filter #(contains? how %) how-keys))]
         (case k
           :command (and (vector? (:command how)) (seq (:command how))
                         (every? string? (:command how))
                         (valid-fixture? (:fixture how)))
           :middleware (symbol? (:middleware how))
           :predicate (symbol? (:predicate how))
           :contract (keyword? (:contract how))
           false))))

(defn- prove-command-hook!
  "The must-be-able-to-fail rule: when a command hook carries a :fixture proof
  spec ({:argv vector :good string :bad string}) whose argv mentions the literal
  {fixture} token, run both fixtures; their verdicts must differ. A hook that
  cannot produce fail is worse than no hook, so it is rejected before it is
  stored. The runtime :command and the proof :fixture/:argv are distinct: the
  fixture token exists only in the proof template."
  [how timeout-ms]
  (let [{:keys [command fixture]} how
        {:keys [argv good bad]} fixture]
    (when (and (vector? argv)
               (some? good) (some? bad)
               (some #(str/includes? % "{fixture}") argv))
      (let [dir (str (System/getProperty "java.io.tmpdir") "/faraday-fixture-" (System/nanoTime))
            _ (.mkdirs (java.io.File. dir))
            good-file (str dir "/good.txt")
            bad-file (str dir "/bad.txt")
            _ (spit good-file (str good))
            _ (spit bad-file (str bad))
            argv' (fn [f] (mapv #(str/replace % "{fixture}" f) argv))
            good-r (run-command (argv' good-file) timeout-ms)
            bad-r (run-command (argv' bad-file) timeout-ms)
            verdict (fn [r] (if (and (not (:timeout? r)) (zero? (:exit r))) :pass :fail))]
        (try
          (when (= (verdict good-r) (verdict bad-r))
            (fail! (str "hook cannot distinguish its known-good and known-bad fixtures "
                        "(both " (name (verdict good-r)) "); it could not have failed for the "
                        "reason stated in :why, so it is rejected")
                   {:how how}))
          (finally
            (doseq [f [good-file bad-file]] (try (.delete (java.io.File. f)) (catch Exception _ nil)))
            (try (.delete (java.io.File. dir)) (catch Exception _ nil))))))))

(defn hook-add!
  "Register (or re-register) a hook. how/where/why are required; how is a map
  with exactly one of :command/:middleware/:predicate/:contract. A command hook
  with :fixture-good/:fixture-bad is proven able-to-fail before insert.
  Idempotent by content: the id hashes the hook's shape, so re-adding the same
  hook updates it in place."
  [h {:keys [project-id scope-id cell position kind how why gate priority enabled timeout-ms]}]
  (non-empty-string! project-id :project-id)
  (non-empty-string! scope-id :scope-id)
  (non-empty-string! why :why)
  (let [cell (name cell)
        position (name position)
        kind (name kind)
        gate (name (or gate "advisory"))]
    (when-not (contains? cells cell) (fail! (str "unknown cell " cell) {:cell cell}))
    (when-not (contains? positions position) (fail! (str "unknown position " position) {:position position}))
    (when-not (contains? kinds kind) (fail! (str "unknown hook kind " kind) {:kind kind}))
    (when-not (contains? gates gate) (fail! (str "unknown gate " gate) {:gate gate}))
    (when-not (valid-how? how)
      (fail! "how must be a map with exactly one of :command/:middleware/:predicate/:contract" {:how how}))
    (prove-command-hook! how timeout-ms)
    (let [id (short-id "hook-" (str project-id "|" scope-id "|" cell "|" position "|" kind "|" (pr-str how) "|" why))
          ts (now)]
      (locking (:lock h)
        (let [conn (:conn h)
              existing (first (sql/query conn "select id from hooks where id = ?" [id]))]
          (if existing
            (do (sql/query conn
                           (str "update hooks set gate = ?, priority = ?, enabled = ?, timeout_ms = ?, updated_at = ? "
                                "where id = ?")
                           [gate (or priority 0) (if (false? enabled) 0 1) timeout-ms ts id])
                (hook h id))
            (do (sql/query conn
                           (str "insert into hooks (id, project_id, scope_id, cell, position, kind, how, why, gate, priority, enabled, timeout_ms, created_at, updated_at) "
                                "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                           [id project-id scope-id cell position kind (pr-str how) why gate
                            (or priority 0) (if (false? enabled) 0 1) timeout-ms ts ts])
                (hook h id))))))))

(defn hook
  [h hook-id]
  (locking (:lock h)
    (when-let [row (first (sql/query (:conn h) "select * from hooks where id = ?" [hook-id]))]
      (update row :how #(parse-edn % {})))))

(defn hook-list
  "All hooks (enabled and disabled) for a project, shallowest scope first, then
  cell, position, priority. Each row's :how is parsed back to EDN."
  [h project-id]
  (locking (:lock h)
    (mapv (fn [r] (-> r
                      (update :how #(parse-edn % {}))
                      (assoc :scope-rel-path (:rel_path r))
                      (dissoc :rel_path)))
          (sql/query (:conn h)
                     (str "select h.*, s.rel_path from hooks h join scopes s on h.scope_id = s.id "
                          "where h.project_id = ? order by length(s.rel_path), s.rel_path, h.cell, h.position, h.priority")
                     [project-id]))))

(defn hook-set-enabled!
  [h hook-id enabled]
  (locking (:lock h)
    (sql/query (:conn h) "update hooks set enabled = ?, updated_at = ? where id = ?"
               [(if enabled 1 0) (now) hook-id])
    (hook h hook-id)))

(defn hook-remove!
  [h hook-id]
  (locking (:lock h)
    (sql/query (:conn h) "delete from hooks where id = ?" [hook-id])
    :removed))

;; -- tasks -------------------------------------------------------------------

(defn task-add!
  [h {:keys [project-id scope-id title body status priority epic-id manifest assignee dedup-key]}]
  (non-empty-string! project-id :project-id)
  (non-empty-string! title :title)
  (let [id (short-id "faraday-" (str (java.util.UUID/randomUUID)))
        ts (now)
        status (or status "open")
        priority (or priority "normal")]
    (locking (:lock h)
      (let [conn (:conn h)
            existing (when (seq dedup-key)
                       (first (sql/query conn "select id from tasks where dedup_key = ?" [dedup-key])))]
        (if existing
          (task h (:id existing))
          (do (sql/query conn
                         (str "insert into tasks (id, project_id, scope_id, title, body, status, priority, epic_id, manifest, assignee, dedup_key, created_at, updated_at, closed_at) "
                              "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                         [id project-id scope-id title (or body "") status priority epic-id
                          (when manifest (pr-str manifest)) assignee dedup-key ts ts nil])
              (task h id)))))))

(defn task
  [h task-id]
  (locking (:lock h)
    (when-let [row (first (sql/query (:conn h) "select * from tasks where id = ?" [task-id]))]
      (update row :manifest #(parse-edn % nil)))))

(defn task-list
  [h project-id & {:keys [status]}]
  (locking (:lock h)
    (let [rows (if status
                 (sql/query (:conn h) "select * from tasks where project_id = ? and status = ? order by priority desc, created_at" [project-id status])
                 (sql/query (:conn h) "select * from tasks where project_id = ? order by status, priority desc, created_at" [project-id]))]
      (mapv #(update % :manifest (fn [m] (parse-edn m nil))) rows))))

(defn task-set-status!
  [h task-id status]
  (locking (:lock h)
    (let [ts (now)
          closed (if (contains? #{"done" "cancelled"} status) ts nil)]
      (sql/query (:conn h) "update tasks set status = ?, updated_at = ?, closed_at = ? where id = ?"
                 [status ts closed task-id])
      (task h task-id))))

(defn task-set-manifest!
  "Persist a loop-as-data manifest EDN onto a task; nil clears it. Returns the
  updated task. This is the taskboard surface the supervisor writes when a
  proposed loop rewrite passes the gate."
  [h task-id manifest]
  (locking (:lock h)
    (sql/query (:conn h) "update tasks set manifest = ?, updated_at = ? where id = ?"
               [(when manifest (pr-str manifest)) (now) task-id])
    (task h task-id)))

;; -- supervisor_runs: the supervisor's honesty ledger -------------------------

(defn record-supervisor-run!
  "Append a supervisor event row: one proposed-rewrite attempt with its verdict.
  Every proposal leaves a row naming what it observed, what it proposed, and how
  the gate ruled — the supervisor's honesty ledger."
  [h {:keys [task-id observed-status baseline-passed proposal candidate-key verdict
              soak-runs pointer promoted-key]}]
  (let [id (str "sup-" (java.util.UUID/randomUUID))]
    (locking (:lock h)
      (sql/query (:conn h)
                 (str "insert into supervisor_runs (id, task_id, observed_status, baseline_passed, proposal, candidate_key, verdict, soak_runs, pointer, promoted_key, created_at) "
                      "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                 [id task-id observed-status (if baseline-passed 1 0)
                  (when proposal (pr-str proposal)) candidate-key (name verdict)
                  (or soak-runs 0) pointer promoted-key (now)]))
    id))

(defn supervisor-runs-for-task
  "All supervisor events for a task, most recent last."
  [h task-id]
  (locking (:lock h)
    (mapv (fn [r] (-> r
                      (update :proposal #(parse-edn % nil))
                      (update :baseline_passed #(= 1 %))
                      (update :verdict keyword)))
          (sql/query (:conn h) "select * from supervisor_runs where task_id = ? order by created_at" [task-id]))))

;; -- resolution: scope -> loop-as-data hooks ---------------------------------

(defn- scope-prefix? [scope target]
  (or (= scope "")
      (= scope target)
      (str/starts-with? target (str scope "/"))))

(defn- group-hooks [hooks]
  (reduce (fn [acc hk]
            (update-in acc [(keyword (:cell hk)) (keyword (:position hk))] (fnil conj []) hk))
          {} hooks))

(defn resolve-hooks
  "Resolve every enabled hook whose scope rel_path is a path prefix of
  scope-rel-path, shallowest scope first, then cell/position/priority. Returns
  {:project-id :scope :hooks {cell {position [hook ...]}} :hooks-vector [...]} —
  the reified :hooks map the loop compiles into middleware."
  [h project-id scope-rel-path]
  (let [target (normalize-rel-path scope-rel-path)
        rows (sql/query (:conn h)
                        (str "select h.*, s.rel_path as scope_rel from hooks h join scopes s on h.scope_id = s.id "
                             "where h.project_id = ? and h.enabled = 1 "
                             "order by length(s.rel_path), s.rel_path, h.cell, h.position, h.priority")
                        [project-id])
        hooks (->> rows
                   (filter #(scope-prefix? (:scope_rel %) target))
                   (mapv (fn [r] (-> r
                                     (update :how #(parse-edn % {}))
                                     (assoc :scope-rel-path (:scope_rel r))
                                     (dissoc :scope_rel)))))]
    {:project-id project-id
     :scope target
     :hooks (group-hooks hooks)
     :hooks-vector hooks}))

(defn task-start
  "Reify a task into loop-as-data: mark it in_progress (when open) and resolve
  its scope's nested hooks into a :hooks map. Returns the task's loop-as-data
  description."
  [h task-id]
  (let [t (task h task-id)]
    (when-not t (fail! (str "no such task " task-id) {:task-id task-id}))
    (let [project-id (:project_id t)
          scope-rel (if (:scope_id t)
                      (normalize-rel-path (:rel_path (scope h (:scope_id t))))
                      "")]
      (when (= "open" (:status t))
        (task-set-status! h task-id "in_progress"))
      (let [res (resolve-hooks h project-id scope-rel)]
        {:task task-id
         :title (:title t)
         :project project-id
         :scope scope-rel
         :hooks (:hooks res)
         :hooks-vector (:hooks-vector res)
         :manifest (:manifest t)
         :default-manifest (:default_manifest (project h project-id))}))))

;; -- hook_runs: the honesty ledger -------------------------------------------

(defn record-hook-run!
  [h {:keys [hook-id task-id turn-id cell position verdict exit-code output-sha output-ms ran-at]}]
  (let [id (str "run-" (java.util.UUID/randomUUID))]
    (locking (:lock h)
      (sql/query (:conn h)
                 (str "insert into hook_runs (id, hook_id, task_id, turn_id, cell, position, verdict, exit_code, output_sha, ms, ran_at) "
                      "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
                 [id hook-id task-id turn-id (name cell) (name position) (name verdict)
                  exit-code output-sha (or output-ms 0) (or ran-at (now))]))
    id))

(defn hook-runs-for-turn
  [h turn-id]
  (locking (:lock h)
    (sql/query (:conn h) "select * from hook_runs where turn_id = ? order by ran_at" [turn-id])))

(defn expected-ran?
  "The honesty-ledger check: every expected hook id must have at least one
  hook_runs row for turn-id. Returns {:ok bool :missing [ids] :ran-count n}."
  [h turn-id expected-hook-ids]
  (let [rows (hook-runs-for-turn h turn-id)
        ran (set (map :hook_id rows))
        missing (remove ran expected-hook-ids)]
    {:ok (empty? missing)
     :missing (vec missing)
     :ran-count (count rows)}))

(defn turn-green?
  "Finalize the turn against the honesty invariant: every expected hook has a
  row, and every blocking hook has a pass row. Returns {:green? bool :missing
  [ids] :blocking-failed [ids] :verdicts [{:hook-id :gate :verdict [..]}]}."
  [h turn-id expected-hooks]
  (let [rows (hook-runs-for-turn h turn-id)
        by-hook (group-by :hook_id rows)
        expected-ids (map :id expected-hooks)
        missing (remove #(contains? by-hook %) expected-ids)
        blocking-failed (for [hk expected-hooks
                              :when (and (= "blocking" (:gate hk))
                                         (not-any? #(= "pass" (:verdict %)) (get by-hook (:id hk))))]
                          (:id hk))]
    {:green? (and (empty? missing) (empty? blocking-failed))
     :missing (vec missing)
     :blocking-failed (vec blocking-failed)
     :verdicts (mapv (fn [hk] {:hook-id (:id hk)
                               :gate (:gate hk)
                               :verdict (mapv :verdict (get by-hook (:id hk)))})
                     expected-hooks)}))
