;; faraday-contracts: the narrow waist for jolt control-plane wire shapes.
;; Each wire shape is declared ONCE with define-contract; the decoder, encoder
;; and JSON-Schema projection are derived from that declaration, never
;; restated. contracts/SPEC.md is the prose authority; these declarations are
;; its executable form. decode reads a JSON object (string keys) or an
;; in-process keyword map and materializes the validated keyword map; encode
;; is its inverse, producing the string-keyed wire object. A field that fails
;; yields a refusal map, not a throw; decode!/encode! throw on refusal.
(ns faraday.contracts
  "Declared data contracts for a jolt-based control plane.

  One declaration per wire shape. define-contract states the members, their
  types and invariants ONCE; the decoder, encoder and JSON-Schema projection
  are derived from it, never restated. contracts/SPEC.md is the prose
  authority; these declarations are its executable form."
  )

(def ^:private registry (atom {}))

(defn- text? [v] (and (string? v) (seq v)))

(def ^:private type-table
  {:any     {:json-type nil       :check (constantly true) :doc "a value"}
   :string  {:json-type "string"  :check string?            :doc "a string"}
   :text    {:json-type "string"  :check text?              :doc "a non-empty string"}
   :integer {:json-type "integer" :check integer?           :doc "an integer"}
   :number  {:json-type "number"  :check number?            :doc "a number"}
   :boolean {:json-type "boolean" :check boolean?           :doc "a boolean"}
   :object  {:json-type "object"  :check map?               :doc "an object"}
   :array   {:json-type "array"   :check vector?            :doc "an array"}})

(defn- assoc-if [m k opts]
  (if (contains? opts k) (assoc m k (get opts k)) m))

(defn field
  "Build one member of a contract declaration: a literal wire key and its
  type, plus the options :required, :default, :enum, :of, :nullable, :check
  and :doc."
  [key type opts]
  (when-not (contains? type-table type)
    (throw (ex-info (str "unknown contract field type: " type) {:key key :type type})))
  (-> {:key key :wire (name key) :type type
       :required (if (contains? opts :required) (:required opts) true)
       :doc (:doc opts)}
      (assoc-if :default opts)
      (assoc-if :enum opts)
      (assoc-if :of opts)
      (assoc-if :nullable opts)
      (assoc-if :check opts)))

(defn register!
  "Put a contract declaration on the registry under cname, replacing a
  declaration of the same name in place."
  [cname fields]
  (let [c {:name cname :fields (vec fields)}]
    (swap! registry assoc cname c)
    c))

(defn contract
  "The contract declaration for name (a keyword or a descriptor map)."
  [name]
  (let [cname (if (map? name) (:name name) name)
        c (get @registry cname)]
    (when-not c
      (throw (ex-info (str "unknown contract: " cname) {:contract cname})))
    c))

(defn contracts
  "All registered contracts, keyed by name."
  []
  @registry)

(defn refusal
  "A refusal map naming the first member that did not satisfy the declaration."
  [c path expected received]
  {:faraday.contracts/refusal true
   :contract (:name c)
   :path (vec path)
   :expected expected
   :received received})

(defn refusal?
  "True when x is a refusal (a decode or encode that failed the contract)."
  [x]
  (and (map? x) (:faraday.contracts/refusal x)))

(defn- refuse! [r]
  (throw (ex-info (str "contract " (name (:contract r)) " refused at "
                       (pr-str (:path r)) ": expected " (:expected r)
                       ", received " (pr-str (:received r)))
                  {:faraday.contracts/refusal r})))

(declare decode encode schema)

(defn- read-field [obj f]
  (let [w (:wire f) k (:key f)]
    (cond (contains? obj w) [:present (get obj w)]
          (contains? obj k) [:present (get obj k)]
          :else [:absent])))

(defn- type-refusal? [c f v path]
  (let [chk (get-in type-table [(:type f) :check])]
    (when (and chk (not (chk v)))
      (refusal c path (str "a " (name (:type f))) v))))

(defn- enum-refusal? [c f v path]
  (let [enum (:enum f)]
    (when (and enum (not (contains? enum v)))
      (refusal c path (str "one of " (pr-str enum)) v))))

(defn- custom-refusal? [c f v path]
  (let [chk (:check f)]
    (when (and chk (not (chk v)))
      (refusal c path "field invariant failed" v))))

(defn- decode-element [c of v path]
  (if (contains? type-table of)
    (let [chk (get-in type-table [of :check])]
      (if (and chk (not (chk v)))
        (refusal c path (str "a " (name of)) v)
        v))
    (decode (contract of) v path)))

(defn- decode-nested [c f v path]
  (if (= :array (:type f))
    (loop [vs (seq v) i 0 acc []]
      (if vs
        (let [d (decode-element c (:of f) (first vs) (conj path i))]
          (if (refusal? d)
            d
            (recur (next vs) (inc i) (conj acc d))))
        (vec acc)))
    (decode-element c (:of f) v path)))

(defn- decode-value [c f v path]
  ;; v is non-nil. Returns [:ok decoded] or [:refusal r].
  (let [p (conj path (:wire f))]
    (or (when-let [r (type-refusal? c f v p)] [:refusal r])
        (when-let [r (enum-refusal? c f v p)] [:refusal r])
        (let [d (if (:of f) (decode-nested c f v p) v)]
          (if (refusal? d)
            [:refusal d]
            (if-let [r (custom-refusal? c f v p)]
              [:refusal r]
              [:ok d]))))))

(defn decode
  "Materialize obj (a decoded JSON object, string-keyed, or an in-process
  keyword map) as the record contract declares: every member typed, defaulted
  and checked before the boundary owner sees it. Returns the validated keyword
  map, or a refusal naming the first member that failed."
  ([contract-name obj] (decode contract-name obj []))
  ([contract-name obj path]
   (let [c (contract contract-name)]
     (if-not (map? obj)
       (refusal c path "an object" obj)
       (loop [fields (:fields c) acc {}]
         (if-let [f (first fields)]
           (let [[presence v] (read-field obj f)]
             (if (= presence :present)
               (if (nil? v)
                 (if (:nullable f)
                   (recur (rest fields) (assoc acc (:key f) nil))
                   (refusal c (conj path (:wire f)) (str "a " (name (:type f)) ", not null") v))
                 (let [r (decode-value c f v path)]
                   (if (= :refusal (first r))
                     (second r)
                     (recur (rest fields) (assoc acc (:key f) (second r))))))
               (if (:required f)
                 (if (contains? f :default)
                   (recur (rest fields) (assoc acc (:key f) (:default f)))
                   (refusal c (conj path (:wire f)) "present (required)" ::missing))
                 (if (contains? f :default)
                   (recur (rest fields) (assoc acc (:key f) (:default f)))
                   (recur (rest fields) acc)))))
           acc))))))

(defn- encode-element [c of v path]
  (if (contains? type-table of)
    (let [chk (get-in type-table [of :check])]
      (if (and chk (not (chk v)))
        (refusal c path (str "a " (name of)) v)
        v))
    (encode (contract of) v path)))

(defn- encode-nested [c f v path]
  (if (= :array (:type f))
    (loop [vs (seq v) i 0 acc []]
      (if vs
        (let [d (encode-element c (:of f) (first vs) (conj path i))]
          (if (refusal? d)
            d
            (recur (next vs) (inc i) (conj acc d))))
        (vec acc)))
    (encode-element c (:of f) v path)))

(defn- encode-value [c f v path]
  ;; v is non-nil. Returns [:ok encoded] or [:refusal r].
  (let [p (conj path (:wire f))]
    (or (when-let [r (type-refusal? c f v p)] [:refusal r])
        (when-let [r (enum-refusal? c f v p)] [:refusal r])
        (let [d (if (:of f) (encode-nested c f v p) v)]
          (if (refusal? d)
            [:refusal d]
            (if-let [r (custom-refusal? c f v p)]
              [:refusal r]
              [:ok d]))))))

(defn encode
  "Encode record (the keyword-keyed map decode materialized) back to the
  string-keyed wire object it came from, so a producer in this image builds
  the payload the consumer decodes through the same declaration. Returns the
  wire object, or a refusal naming the first member that failed."
  ([contract-name record] (encode contract-name record []))
  ([contract-name record path]
   (let [c (contract contract-name)]
     (if-not (map? record)
       (refusal c path "a record (map)" record)
       (loop [fields (:fields c) acc {}]
         (if-let [f (first fields)]
           (if (contains? record (:key f))
             (let [v (get record (:key f))]
               (if (nil? v)
                 (if (:nullable f)
                   (recur (rest fields) (assoc acc (:wire f) nil))
                   (refusal c (conj path (:wire f)) (str "a " (name (:type f)) ", not null") v))
                 (let [r (encode-value c f v path)]
                   (if (= :refusal (first r))
                     (second r)
                     (recur (rest fields) (assoc acc (:wire f) (second r)))))))
             (if (:required f)
               (if (contains? f :default)
                 (recur (rest fields) (assoc acc (:wire f) (:default f)))
                 (refusal c (conj path (:wire f)) "present (required)" ::missing))
               (if (contains? f :default)
                 (recur (rest fields) (assoc acc (:wire f) (:default f)))
                 (recur (rest fields) acc))))
           acc))))))

(defn decode!
  "Materialize obj as the record contract declares; throw on refusal."
  [contract-name obj]
  (let [r (decode contract-name obj)]
    (if (refusal? r) (refuse! r) r)))

(defn encode!
  "Encode record to the wire object contract declares; throw on refusal."
  [contract-name record]
  (let [r (encode contract-name record)]
    (if (refusal? r) (refuse! r) r)))

(defn valid?
  "True when obj satisfies the contract declaration."
  [contract-name obj]
  (not (refusal? (decode contract-name obj))))

(defn- field-schema [f]
  (let [t (:type f)
        base (if-let [jt (get-in type-table [t :json-type])] {:type jt} {})
        base (if (and (= :array t) (:of f))
               (assoc base :items
                      (if (contains? type-table (:of f))
                        (or (get-in type-table [(:of f) :json-type]) {})
                        (schema (:of f))))
               base)
        base (if (and (= :object t) (:of f) (not (contains? type-table (:of f))))
               (assoc base :$ref (str "#/definitions/" (name (:of f))))
               base)
        base (if (:enum f) (assoc base :enum (vec (:enum f))) base)
        base (if (:doc f) (assoc base :description (:doc f)) base)]
    base))

(defn schema
  "Contract as the JSON-Schema object a catalog advertises: one property per
  declared member with its type, enum and line of documentation, and the
  required list."
  [contract-name]
  (let [c (contract contract-name)
        props (reduce (fn [m f] (assoc m (:wire f) (field-schema f))) {} (:fields c))
        req (mapv :wire (filter :required (:fields c)))]
    {:type "object" :properties props :required req}))

(defmacro define-contract
  "Declare the boundary payload cname-sym: its members with their literal wire
  keys and their types, and -- derived from that, never restated -- the record
  it materializes as, the decoder that materializes it, the encoder that is the
  decoder inverse, and the JSON-Schema projection a catalog advertises."
  [cname-sym & clauses]
  (let [cname (keyword (clojure.core/name cname-sym))
        fields (map (fn [[k t & kvs]] `(field ~k ~t (hash-map ~@kvs))) clauses)]
    `(do (def ~cname-sym (register! ~cname [~@fields])) ~cname)))

;; -- shared nested shapes ----------------------------------------------------

(define-contract display-segment
  (:type :string :required true :enum #{"text" "file" "json"}
   :doc "first type the client supports")
  (:content :any :required true
   :check (fn [v] (or (string? v) (map? v)))
   :doc "string or object")
  (:path :string :required false :doc "file path when type is file")
  (:mime :string :required false :doc "MIME type when type is file"))

(define-contract mount
  (:host-path :string :required true :doc "host directory to share")
  (:tag :string :required true :doc "virtiofs tag, unique within the request")
  (:readonly :boolean :required false :default false :doc "read-only mount"))

(define-contract tool-error
  (:message :string :required true :doc "human error message")
  (:code :string :required false :doc "stable machine error code"))

(define-contract cost
  (:cpu :integer :required true :doc "vCPUs charged")
  (:mem-mb :integer :required true :doc "memory charged in MiB"))

;; -- the six SPEC.md shapes --------------------------------------------------

(define-contract thread-message
  (:v :integer :required true :doc "always 1")
  (:thread-id :string :required true :doc "root thread this message belongs to")
  (:parent-id :string :required true :nullable true
   :doc "parent thread, null at the root")
  (:msg-id :string :required true :doc "global idempotency key")
  (:seq :integer :required false
   :doc "per-thread monotonic sequence, assigned by the ledger")
  (:kind :string :required true
   :enum #{"spawn" "inherit" "report" "yield" "user-input" "system"}
   :doc "message kind")
  (:ts :string :required true :doc "RFC 3339 UTC timestamp")
  (:payload :object :required true :doc "kind-specific payload"))

(define-contract repo-state
  (:mode :string :required true :enum #{"git" "jj" "direct"}
   :doc "how the worktree is materialized")
  (:url :string :required false :doc "remote URL for :git/:jj")
  (:ref :string :required false :doc "branch or commit to check out")
  (:host-path :string :required false :doc "existing host worktree for :direct"))

(define-contract sandbox-request
  (:v :integer :required true :doc "always 1")
  (:request-id :string :required true :doc "idempotency key for this boot")
  (:image :string :required true :doc "OCI ref or local path")
  (:argv :array :required true :of :string
   :check (fn [v] (seq v))
   :doc "argv[0] is the program; no shell wrapping")
  (:env :object :required false :doc "environment passed to the guest")
  (:mounts :array :required false :of :mount :doc "virtiofs mounts")
  (:repo :object :required false :of :repo-state
   :doc "persistent worktree keyed to the owning thread")
  (:cpu :integer :required false :default 1 :doc "vCPU count")
  (:mem-mb :integer :required false :doc "memory limit in MiB")
  (:timeout-ms :integer :required false :doc "hard kill after this long")
  (:egress :object :required false
   :check (fn [v] (or (contains? v "ref") (contains? v :ref)
                      (contains? v "egress-id") (contains? v :egress-id)))
   :doc "egress ref or inline Egress Contract")
  (:stdin :string :required false :nullable true
   :doc "full stdin; null or absent means closed"))

(define-contract egress-contract
  (:v :integer :required true :doc "always 1")
  (:egress-id :string :required true :doc "identity of this policy")
  (:dns :array :required true :of :string :doc "upstream DNS servers the guest may query")
  (:allow-cidrs :array :required true :of :string :doc "outbound CIDRs permitted")
  (:deny-cidrs :array :required false :of :string :doc "outbound CIDRs dropped")
  (:host-gateway :boolean :required true :doc "allow the TAP host gateway")
  (:default :string :required true :enum #{"deny"}
   :doc "deny (only deny in v1)"))

(define-contract tool-invocation
  (:v :integer :required true :doc "always 1")
  (:call-id :string :required true :doc "idempotency key for this invocation")
  (:thread-id :string :required true :doc "owning thread")
  (:name :string :required true
   :check (fn [v] (not (re-find #"\s" v)))
   :doc "tool name, no whitespace")
  (:args :object :required true :doc "tool arguments (tool-defined schema)")
  (:display-as :string :required false :doc "human summary shown by UIs"))

(define-contract tool-result
  (:v :integer :required true :doc "always 1")
  (:call-id :string :required true :doc "idempotency key for this invocation")
  (:status :string :required true :enum #{"ok" "error" "cancelled"}
   :doc "terminal status")
  (:segments :array :required true :of :display-segment
   :doc "may be empty")
  (:exit-code :integer :required false :nullable true
   :doc "process exit code when applicable")
  (:error :object :required false :nullable true :of :tool-error
   :doc "present on error"))

(define-contract budget-permit
  (:v :integer :required true :doc "always 1")
  (:permit-id :string :required true :doc "idempotency key")
  (:owner :string :required true :doc "thread holding the permit")
  (:cost :object :required true :of :cost :doc "cpu and mem-mb charged")
  (:priority :integer :required false :doc "higher wins under contention")
  (:timeout-ms :integer :required false :doc "0 blocks forever")
  (:status :string :required true :enum #{"granted" "queued" "denied" "released"}
   :doc "permit status"))

(define-contract policy-set
  (:v :integer :required true :doc "always 1")
  (:policies :array :required true :of :string :doc "Cedar policy source texts")
  (:schema :string :required false :doc "Cedar schema JSON")
  (:entities :array :required false :of :object :doc "Cedar entity JSON array")
  (:context :object :required false :doc "Cedar request context"))
