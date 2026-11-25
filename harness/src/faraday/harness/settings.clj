;; faraday.harness.settings — the /settings and /config editor surface.
;; /settings flattens the resolved config (defaults < user < project) into
;; dialog rows; edits apply to that resolved map and materialize the whole thing
;; into the user settings file (~/.faraday/settings.json, or FARADAY_SETTINGS),
;; exactly as nodecode's settings editor shows every setting and writes back the
;; one you changed. /config browses the same resolved map read-only.
;;
;; This namespace supersedes the old llm-opts resolver, which config.clj now
;; covers (resolve-provider / resolve-*).
;;
;; Rows are flat with :depth indentation (the dialog list is flat), so no tree
;; folding is needed; every leaf carries :path (a vector of keys) and edits hit
;; the source map by path — never the filtered index, which shifts as you type.
(ns faraday.harness.settings
  "Flatten a settings map to dialog rows, edit a path, and persist."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [faraday.harness.config :as cfg]))

(def ^:private secret-key-re #"(?i)(api.?key|token|secret|password|passwd)$")
(def masked "••••••••")

(defn secret-key?
  "True for keys that name a secret (ends in key/token/secret/password)."
  [k]
  (boolean (re-find secret-key-re (name k))))

(defn- humanize
  "api-key-env -> \"api key env\"."
  [k]
  (str/replace (name k) "-" " "))

(defn- type-of [v]
  (cond
    (boolean? v) :bool
    (number? v) :num
    (string? v) :str
    (vector? v) :vec
    (nil? v) :nil
    (map? v) :map
    :else :other))

(defn- display [v]
  (cond
    (boolean? v) ""
    (nil? v) ""
    (number? v) (str v)
    (vector? v) (str "[" (str/join ", " v) "]")
    :else (str v)))

(defn- path-str [path]
  (str/join "." (map name path)))

(defn rows-for-map
  "Flatten a settings map into flat, depth-indented dialog rows. Each map emits
  a :section header then its children at +1 depth; booleans become :toggle rows
  (with :checked?), strings and numbers :value rows, vectors and other values
  read-only :row rows. Secret values are masked and carry no :raw so a key can
  never leak through a row map. Every leaf carries :path and :type so the shell
  edits the source map by path."
  [m]
  (letfn [(walk [x path depth]
            (if (map? x)
              (mapcat
               (fn [[k v]]
                 (let [p (conj path k)
                       nm (humanize k)]
                   (if (map? v)
                     (cons {:label nm :value (str "(" (count v) ")") :kind :section
                            :path p :depth depth :type :map}
                           (walk v p (inc depth)))
                     (let [secret (secret-key? k)
                           t (type-of v)]
                       [(cond-> {:label nm :path p :depth depth
                                 :kind (case t :bool :toggle (:num :str) :value :row)
                                 :type t
                                 :value (if secret masked (display v))
                                 :desc (path-str p)
                                 :read-only? (contains? #{:vec :other} t)}
                          (boolean? v) (assoc :checked? v)
                          secret (assoc :masked? true)
                          (not secret) (assoc :raw v))]))))
               (sort-by (comp name key) x))
              []))]
    (vec (walk m [] 0))))

(defn- parse-num [s]
  (try (Long/parseLong s)
       (catch Exception _
         (try (Double/parseDouble s)
              (catch Exception _ s)))))

(defn coerce
  "Coerce an edited string back to the row's type: numbers parse, booleans parse
  common spellings, everything else stays a string."
  [type s]
  (case type
    :num (parse-num s)
    :bool (contains? #{"true" "on" "yes" "1"} (str/lower-case (str/trim s)))
    s))

(defn set-value-at-path
  "Set a leaf at :path from an edited string, coercing by the row's :type. A
  blank edit leaves the map unchanged (so a masked secret you left empty is
  never overwritten with blanks)."
  [m path s type]
  (if (str/blank? s)
    m
    (assoc-in m path (coerce type s))))

(defn toggle-at-path
  "Flip the boolean at :path."
  [m path]
  (update-in m path not))

(defn write-settings!
  "Write the settings map to the user settings file (default: FARADAY_SETTINGS
  or ~/.faraday/settings.json; pass `path` to override, as tests and the shell's
  :settings-file seam do). Returns the path written."
  [m & [path]]
  (let [path (or path (cfg/settings-file))]
    (spit path (json/write-str m :pretty true))
    path))

(defn write-leaf!
  "Write only the leaf at path p (value v) into the settings file at path,
  merged over the file's current content; missing parents are created. Returns
  the path written. Unlike write-settings! this never copies the rest of the
  resolved map (builtin defaults, user secrets) into the target layer, so the
  project/user layer split survives an edit."
  [path p v]
  (let [cur (or (cfg/read-json-map path) {})
        out (if (nil? v) cur (assoc-in cur p v))]
    (spit path (json/write-str out :pretty true))
    path))

(defn edit-target-file
  "Pick the settings file an edit should land in: {:path string :project? bool}.
  Rules: an explicit :project-settings-file opt (test seam) always targets the
  project file; else when a project root is set (:project-root or :proj-root)
  and it is a real project (has .faraday/harness.db) or its settings file
  already exists, edits go to <root>/.faraday/settings.json (project wins, so
  that is the only layer where the edit takes effect); otherwise the user file
  (FARADAY_SETTINGS / ~/.faraday/settings.json)."
  [opts]
  (let [user (or (:settings-file opts) (cfg/settings-file))
        proj (or (:project-settings-file opts)
                 (when-let [r (cfg/project-root opts)]
                   (let [p (cfg/project-settings-file r)]
                     (when (or (.isFile (java.io.File. (str r "/.faraday/harness.db")))
                               (.isFile (java.io.File. p)))
                       p))))]
    (if proj
      {:path proj :project? true}
      {:path user :project? false})))

(defn scaffold!
  "Create a starter user settings file when none exists; never overwrites.
  Returns the path."
  [& [path]]
  (let [path (or path (cfg/settings-file))
        f (java.io.File. path)]
    (when-not (.exists f)
      (.mkdirs (.getParentFile f))
      (spit path (json/write-str
                  {:providers {}
                   :roles {}
                   :agents {}
                   :escalation {:max-repairs 3 :max-escalations 2
                                :breaker-threshold 0.4
                                :ladder ["repair" "escalate" "orchestrator"]}
                   :chat {:wrap 80}}
                  :pretty true)))
    path))
