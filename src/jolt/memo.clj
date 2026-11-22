;; jolt-memo: content-addressed result cache.
(ns jolt.memo
  "Content-addressed result cache for the jolt control plane.

  A memo maps the canonical content hash of a value to that value, so
  identical inputs produce one stored copy. jolt-fanout uses it behind a
  Thread Message's optional memo-key (jolt-contracts SPEC.md §1) to skip
  recomputing a spawn result.

  Keys are opaque 64-char lowercase hex strings: the SHA-256 of the value's
  canonical form. Callers must not parse structure out of them. Values must
  be plain EDN data (nil, booleans, numbers, strings, keywords, symbols,
  chars, vectors, lists/seqs, maps, sets). Maps and sets are canonicalized by
  sorting, so any two = values always hash to the same key.

  Storage is write-through: one file per key under :path, named by the key.
  put! is idempotent and atomic (temp file + rename)."
  (:refer-clojure :exclude [get contains?])
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [jolt.memo.sha256 :as sha]))

(defn- join
  "Concatenate xs, inserting sep between adjacent elements."
  [sep xs]
  (apply str (interpose sep xs)))

(defn- canonical
  "Return the deterministic canonical EDN text of value v: any two values
  that are = serialize to the same string."
  [v]
  (cond
    (nil? v) "nil"
    (boolean? v) (str v)
    (string? v) (pr-str v)
    (keyword? v) (pr-str v)
    (symbol? v) (pr-str v)
    (char? v) (pr-str v)
    (number? v) (pr-str v)
    (record? v)
    (throw (ex-info "cannot memoize a record; pass plain EDN data"
                    {:value v :type (type v)}))
    (map? v)
    (str "{"
         (join " "
               (map (fn [[k val]]
                      (str (canonical k) " " (canonical val)))
                    (sort-by (fn [[k _]] (canonical k)) (seq v))))
         "}")
    (set? v)
    (str "#{" (join " " (map canonical (sort-by canonical (seq v)))) "}")
    (vector? v)
    (str "[" (join " " (map canonical v)) "]")
    (seq? v)
    (str "(" (join " " (map canonical v)) ")")
    :else
    (throw (ex-info "cannot memoize a value of this type"
                    {:value v :type (type v)}))))

(defn- digest-hex
  "Lowercase hex SHA-256 of the UTF-8 bytes of s."
  [s]
  (sha/hex-digest (.getBytes s "UTF-8")))

(defn- memo-path [memo] (::path memo))

(defn- key-file [memo key]
  (io/file (memo-path memo) key))

(defn new-memo
  "Open (or create) a memo rooted at opts, a map with at least :path.
  Returns an opaque memo handle."
  [opts]
  (let [path (:path opts)]
    (when-not (string? path)
      (throw (ex-info "new-memo requires a string :path" {:opts opts})))
    (.mkdirs (io/file path))
    {::path path}))

(defn key-of
  "Return the content-hash key for value. Pure: does not store anything.
  Identical values hash to the same key."
  [memo value]
  (digest-hex (canonical value)))

(defn put!
  "Store value under its content-hash key. Returns the key. Idempotent:
  putting an already-present value is a no-op that returns its key."
  [memo value]
  (let [text (canonical value)
        key (digest-hex text)
        f (key-file memo key)]
    (when-not (.exists f)
      (let [tmp (io/file (str (.getPath f) ".tmp." (System/nanoTime)))]
        (spit tmp text :encoding "UTF-8")
        (.renameTo tmp f)))
    key))

(defn get
  "Return the value stored under key, or nil if absent. Callers that must
  distinguish a stored nil use contains?."
  [memo key]
  (let [f (key-file memo key)]
    (when (.exists f)
      (edn/read-string (slurp f :encoding "UTF-8")))))

(defn contains?
  "True iff key is present."
  [memo key]
  (.exists (key-file memo key)))

(defn delete!
  "Remove key. Returns true if it was present, false otherwise."
  [memo key]
  (let [f (key-file memo key)]
    (when (.exists f)
      (.delete f)
      true)))

(defn stats
  "Return {:entries n, :bytes n} for the memo."
  [memo]
  (let [dir (io/file (memo-path memo))]
    (if (.isDirectory dir)
      (reduce (fn [acc f]
                (if (and (.isFile f)
                         (re-matches #"[0-9a-f]{64}" (.getName f)))
                  {:entries (inc (:entries acc))
                   :bytes (+ (:bytes acc) (.length f))}
                  acc))
              {:entries 0 :bytes 0}
              (.listFiles dir))
      {:entries 0 :bytes 0})))

(defn close!
  "Flush and close the memo. Storage is write-through, so nothing is
  buffered; kept for API symmetry with other jolt-plane stores."
  [memo]
  nil)
