;; faraday.themes — the one place Faraday knows about theme files and colours.
;;
;; Model follows jolt's theming: a theme is a data-only TOML pack with [dark]
;; and [light] sections of flat colour tokens, an optional `extends = "name"`,
;; and it lives as <name>.toml in ~/.faraday/themes/ (user) or
;; resources/faraday/themes/ (built-in). settings.json only selects:
;; {"theme": "tungsten", "appearance": "auto"}. Paint sites call (color :user),
;; (color :canvas) — the render path never touches a file or a hex literal.
;;
;; Colour values accepted: #rgb, #rrggbb, rgb(r,g,b), and the literal "default"
;; (terminal default / transparent). A pack inherits every role it does not
;; name from `base` (nil) and, when only one of [dark]/[light] is given, that
;; section serves both modes. Missing/invalid packs fall back to built-in
;; tungsten with a warning and never crash the TUI.
(ns faraday.themes
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [clojure.data.json :as json]))

;; -- roles -------------------------------------------------------------------
(def roles
  "Every colour role a paint site may resolve. Surface roles paint :bg; the
  rest paint :color. nil means transparent / terminal default."
  [:canvas :chrome-bar :sheet :composer :code-slab :selection :hover :key-chip
   :heading-band
   :user-block
   :body :muted :chrome :user :assistant :tool :heading :inline-code :link
   :accent :strong :danger])

  (def ^:private surface-roles
  #{:canvas :chrome-bar :sheet :composer :code-slab :selection :hover :key-chip
    :heading-band :user-block})

(def ^:private fg-roles
  (set (remove surface-roles roles)))

(def ^:private base
  "Structural defaults: every role exists before a pack is merged; nil = the
  terminal's own default/transparent."
  (into {} (map (fn [r] [r nil])) roles))

;; Test seams: nil resolves the real location, a string/File overrides it.
(def ^:dynamic *settings-file*
  "Override for the settings.json read (tests); nil => FARADAY_SETTINGS else
  ~/.faraday/settings.json."
  nil)

(def ^:dynamic *themes-dir*
  "Override for the user themes directory (tests); nil => ~/.faraday/themes."
  nil)

(def ^:dynamic *system-appearance*
  "Override for auto-mode detection (tests); nil => detect, :dark/:light force."
  nil)

;; -- locations ---------------------------------------------------------------
(defn- default-settings-file []
  (or (System/getenv "FARADAY_SETTINGS")
      (str (System/getProperty "user.home") "/.faraday/settings.json")))

(defn- user-themes-dir []
  (str (or *themes-dir*
           (str (System/getProperty "user.home") "/.faraday/themes"))))

(defn- builtin [name]
  (some-> (io/resource (str "faraday/themes/" name ".toml")) slurp))

(defn available
  "Union of built-in and user theme names; a user name shadows a built-in of the
  same name."
  []
  (set (concat
        ["tungsten"]
        (let [d (io/file (user-themes-dir))]
          (when (.isDirectory d)
            (map #(subs (.getName %) 0 (- (count (.getName %)) 5))
                 (filter #(.endsWith (.getName %) ".toml") (.listFiles d))))))))

;; -- colour values -----------------------------------------------------------
(defn- parse-hex [s]
  (let [s (str/trim (str s))]
    (cond
      (re-matches #"#[0-9a-fA-F]{3}" s)
      (let [r (subs s 1 2) g (subs s 2 3) b (subs s 3 4)]
        (str "#" r r g g b b))

      (re-matches #"#[0-9a-fA-F]{6}" s) (str/lower-case s)
      :else nil)))

(defn- parse-rgb [s]
  (when-let [[_ r g b] (re-matches
                        #"rgb\s*\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)"
                        (str/trim (str s)))]
    (let [clamp (fn [n] (max 0 (min 255 (Integer/parseInt n))))]
      (format "#%02x%02x%02x" (clamp r) (clamp g) (clamp b)))))

(defn- parse-color [s]
  "A colour literal to canonical #rrggbb, or nil for \"default\"/transparent."
  (let [s (str/trim (str s))]
    (cond
      (nil? s) nil
      (= "" s) nil
      (= "default" (str/lower-case s)) nil
      :else (or (parse-hex s) (parse-rgb s)))))

(defn- valid-role? [v]
  (or (nil? v)
      (= "default" (str/lower-case (str v)))
      (boolean (parse-color v))))

;; -- the TOML subset ---------------------------------------------------------
;; A pack uses only what jolt's theme files use: [dark]/[light] sections, flat
;; `key = "value"` pairs, # comments, and a top-level `extends = "name"`.
;; Values are quoted strings; an unquoted bare token is accepted leniently.
(defn- quoted-string [s]
  (let [s (str/trim s)]
    (if (str/starts-with? s "\"")
      (loop [i 1]
        (cond
          (>= i (count s)) (subs s 1)
          (= \" (nth s i)) (if (= \\ (nth s (dec i)))
                             (recur (inc i))
                             (subs s 1 i))
          :else (recur (inc i))))
      (let [c (str/index-of s " #")]
        (if c (subs s 0 c) s)))))

(defn- unescape [s]
  (-> s (str/replace "\\\"" "\"") (str/replace "\\\\" "\\")))

(defn- parse-pack
  "The TOML subset to {:extends name-or-nil :dark {role val} :light {role val}}.
  Throws on a malformed section or line; callers treat that as unreadable."
  [text]
  (loop [lines (str/split-lines (str text)) section nil extends nil out {:dark {} :light {}}]
    (if-let [line (first lines)]
      (let [line (str/trim line)]
        (cond
          (str/blank? line) (recur (rest lines) section extends out)
          (str/starts-with? line "#") (recur (rest lines) section extends out)

          (str/starts-with? line "[")
          (let [m (re-matches #"\[\s*([a-zA-Z-]+)\s*\]" line)]
            (when-not m
              (throw (ex-info (str "malformed theme section: " line) {:line line})))
            (let [s (second m)]
              (when-not (contains? #{"dark" "light"} s)
                (throw (ex-info (str "unknown theme section: " line) {:line line})))
              (recur (rest lines) (keyword s) extends out)))

          :else
          (let [[k v] (str/split line #"=" 2)]
            (when-not (and k v)
              (throw (ex-info (str "malformed theme line: " line) {:line line})))
            (let [k (keyword (str/trim k))
                  v (unescape (quoted-string v))]
              (if (= :extends k)
                (recur (rest lines) section v out)
                (recur (rest lines) section extends
                       (assoc-in out [section k] v)))))))
      (cond-> out
        (seq extends) (assoc :extends extends)))))

;; -- discovery, merge, validation --------------------------------------------
(defn- deep-merge [a b]
  (merge-with (fn [x y] (if (and (map? x) (map? y)) (deep-merge x y) y)) a b))

(defn- read-pack [name]
  (try
    (let [uf (io/file (user-themes-dir) (str name ".toml"))]
      (cond
        (.exists uf) (parse-pack (slurp uf))
        (builtin name) (parse-pack (builtin name))
        :else nil))
    (catch Exception _ nil)))

(defn- valid-pack? [pack]
  (and (map? pack)
       (every? valid-role?
               (concat (vals (:dark pack)) (vals (:light pack))))))

(declare resolve-pack)

(defn- resolve-pack
  "base <- (extends parent, recursively) <- this pack's own [dark]/[light].
  One variant defined serves both modes. Returns {:dark {role val} :light
  {role val}} with every role present, or nil when unreadable/invalid."
  [name seen]
  (when-not (contains? seen name)
    (let [pack (read-pack name)]
      (when (and pack (valid-pack? pack))
        (let [parent (when (:extends pack)
                       (resolve-pack (:extends pack) (conj seen name)))
              parent (or parent {:dark {} :light {}})
              d (:dark pack)
              l (:light pack)
              ;; one variant defined => it serves both modes
              d (if (and (empty? d) (seq l)) l d)
              l (if (and (empty? l) (seq d)) d l)]
          {:dark (deep-merge base (deep-merge (:dark parent) d))
           :light (deep-merge base (deep-merge (:light parent) l))})))))

;; -- settings & appearance ---------------------------------------------------
(defn- read-settings []
  (let [f (or *settings-file* (default-settings-file))]
    (try (json/read-str (slurp f) :key-fn keyword)
         (catch Exception _ nil))))

(defn- str-val [v]
  (cond
    (keyword? v) (name v)
    (symbol? v) (name v)
    (string? v) v
    :else nil))

(defn- detect-appearance []
  (if *system-appearance*
    *system-appearance*
    (let [os (str/lower-case (System/getProperty "os.name" ""))]
      (if (str/includes? os "mac")
        (try (if (str/includes?
                  (str/lower-case (:out (sh/sh "defaults" "read" "-g" "AppleInterfaceStyle")))
                  "dark")
               :dark :light)
             (catch Exception _ :dark))
        (let [env (str/lower-case (str (or (System/getenv "GTK_THEME")
                                           (System/getenv "COLORFGBG") "")))]
          (if (str/includes? env "light") :light :dark))))))

(defn- resolve-appearance [settings]
  (case (str-val (:appearance settings))
    "light" :light
    "dark" :dark
    (detect-appearance)))

;; -- the active theme --------------------------------------------------------
(def ^:private theme (atom nil))
(def ^:private mode (atom nil))

(defn- warn* [& msgs]
  (binding [*out* *err*]
    (apply println "faraday.themes:" msgs)))

(defn reload!
  "Resolve the active theme from settings (:theme, :appearance) and cache it.
  Returns the merged pack {:dark .. :light ..}; never nil — an unusable
  selection falls back to built-in tungsten with a warning."
  []
  (let [settings (read-settings)
        want (or (str-val (:theme settings)) "tungsten")
        pack (resolve-pack want #{})]
    (when-not pack
      (warn* (str "theme \"" want "\" unusable; falling back to tungsten")))
    (reset! mode (resolve-appearance settings))
    (reset! theme (or pack
                      (resolve-pack "tungsten" #{})
                      {:dark base :light base}))
    @theme))

(defn appearance []
  "The active mode: :dark or :light."
  (or @mode (do (reload!) @mode)))

  (defn color
  "Resolve one role to a canonical #rrggbb string, or nil for transparent /
  terminal default: (color :user), (color :canvas)."
  [role]
  (parse-color (get-in (or @theme (reload!)) [(appearance) role])))

(defn- hex-rgb [s]
  (let [s (str/replace (str s) "#" "")]
    [(Integer/parseInt (subs s 0 2) 16)
     (Integer/parseInt (subs s 2 4) 16)
     (Integer/parseInt (subs s 4 6) 16)]))

(defn heading-band
  "The heading background for level 1..6: the :heading-band role scaled from
  100% (h1) down to 40% (h6), same hue. nil when the role is unset."
  [level]
  (when-let [base (color :heading-band)]
    (let [lvl (max 1 (min 6 (int level)))
          factor (- 1.0 (* (dec lvl) 0.12))
          [r g b] (hex-rgb base)]
      (format "#%02x%02x%02x" (int (* r factor)) (int (* g factor)) (int (* b factor))))))

;; -- validation (jolt's `theme check`) ---------------------------------------
(defn- luminance [rgb]
  (let [lin (fn [c]
              (let [c (/ c 255.0)]
                (if (<= c 0.03928) (/ c 12.92)
                    (Math/pow (/ (+ c 0.055) 1.055) 2.4))))]
    (+ (* 0.2126 (lin (rgb 0)))
       (* 0.7152 (lin (rgb 1)))
       (* 0.0722 (lin (rgb 2))))))

(defn- contrast [a b]
  (let [la (luminance a) lb (luminance b)]
    (/ (+ (max la lb) 0.05) (+ (min la lb) 0.05))))

(defn check
  "Validation warnings for theme `name`: roles left nil (terminal default),
  invalid values, and WCAG text/canvas contrast below 4.5:1. Contrast is only
  computed when the mode's canvas is a known colour (a transparent canvas
  leaves legibility to the terminal)."
  [name]
  (if-let [pack (resolve-pack name #{})]
    (let [warnings (atom [])]
      (doseq [[m roles] pack
              [role v] roles]
        (when (nil? v)
          (swap! warnings conj (str (name m) "." (name role) " missing; using terminal default")))
        (when (and v (not (valid-role? v)))
          (swap! warnings conj (str (name m) "." (name role) " invalid: " (pr-str v)))))
      (doseq [[m roles] pack
              :let [canvas (parse-color (:canvas roles))]
              :when canvas
              [role v] roles
              :when (and (fg-roles role) (parse-color v))]
        (let [ratio (contrast (hex-rgb canvas) (hex-rgb (parse-color v)))]
          (when (< ratio 4.5)
            (swap! warnings conj (str (name m) "." (name role) " contrast "
                                     (format "%.2f" ratio) " < 4.5")))))
      @warnings)
    ["theme not found or invalid"]))
