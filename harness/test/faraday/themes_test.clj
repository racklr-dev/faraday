;; faraday.themes acceptance: TOML packs, [dark]/[light], extends, colour
;; formats, appearance modes, fallback + shadowing, and check warnings.
;; Hermetic: every section binds a temp settings file and themes dir.
(ns jolt.test.themes
  (:require [faraday.themes :as themes]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))
(defn check-true [label got] (check label true got))

(defn- tmp-file [suffix content]
  (doto (java.io.File/createTempFile "faraday-themes" suffix)
    (spit content)))

(defn- tmp-dir []
  (doto (java.io.File/createTempFile "faraday-themes-dir" ".d")
    (.delete)
    (.mkdirs)))

;; -- default: no user themes, no settings -> built-in tungsten (dark) --------
(binding [themes/*settings-file* (tmp-file ".json" "{}")
          themes/*themes-dir* (tmp-dir)
          themes/*system-appearance* :dark]
  (themes/reload!)
  (check "default canvas is transparent" nil (themes/color :canvas))
  (check "default composer" "#3b3937" (themes/color :composer))
  (check "default user is orange" "#fb923c" (themes/color :user))
  (check "default assistant is teal" "#55bfa3" (themes/color :assistant))
  (check "default tool is amber" "#fca864" (themes/color :tool))
  (check "default link is info blue" "#6ea8ff" (themes/color :link))
  (check "default strong is amber" "#fca864" (themes/color :strong))
  (check "default danger" "#ef4444" (themes/color :danger))
  (check "default slab" "#1b2125" (themes/color :code-slab)))

;; -- {"theme":"tungsten"} and {} are identical -------------------------------
(binding [themes/*themes-dir* (tmp-dir)
          themes/*system-appearance* :dark]
  (let [a (binding [themes/*settings-file* (tmp-file ".json" "{}")]
            (themes/reload!)
            (mapv #(themes/color %) themes/roles))
        b (binding [themes/*settings-file* (tmp-file ".json" "{\"theme\":\"tungsten\"}")]
            (themes/reload!)
            (mapv #(themes/color %) themes/roles))]
    (check "{} and {\"theme\":\"tungsten\"} render identically" a b)))

;; -- colour formats ----------------------------------------------------------
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "f.toml")
          "[dark]\nuser = \"#f0f\"\nassistant = \"rgb(85, 191, 163)\"\n")
    (binding [themes/*themes-dir* d
              themes/*settings-file* (tmp-file ".json" "{\"theme\":\"f\"}")]
      (themes/reload!)
      (check "#rgb expands" "#ff00ff" (themes/color :user))
      (check "rgb() parses" "#55bfa3" (themes/color :assistant)))))

;; -- partial pack: only named roles change, the rest inherit base (nil) -----
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "partial.toml") "[dark]\nuser = \"#ffffff\"\n")
    (binding [themes/*themes-dir* d
              themes/*settings-file* (tmp-file ".json" "{\"theme\":\"partial\"}")]
      (themes/reload!)
      (check "partial pack sets user" "#ffffff" (themes/color :user))
      (check "partial pack inherits base nil for assistant" nil (themes/color :assistant)))))

;; -- one variant serves both modes -------------------------------------------
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "one.toml") "[dark]\nuser = \"#222222\"\n")
    (binding [themes/*themes-dir* d
              themes/*settings-file* (tmp-file ".json" "{\"theme\":\"one\",\"appearance\":\"light\"}")]
      (themes/reload!)
      (check "one dark variant serves light mode" "#222222" (themes/color :user)))))

;; -- extends inheritance -----------------------------------------------------
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "child.toml")
          "extends = \"tungsten\"\n[dark]\nuser = \"#111111\"\n")
    (binding [themes/*themes-dir* d
              themes/*settings-file* (tmp-file ".json" "{\"theme\":\"child\"}")]
      (themes/reload!)
      (check "extends overrides user" "#111111" (themes/color :user))
      (check "extends inherits assistant" "#55bfa3" (themes/color :assistant)))))

;; -- user tungsten shadows built-in ------------------------------------------
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "tungsten.toml") "[dark]\nuser = \"#123456\"\n")
    (binding [themes/*themes-dir* d
              themes/*settings-file* (tmp-file ".json" "{\"theme\":\"tungsten\"}")]
      (themes/reload!)
      (check "user tungsten shadows built-in" "#123456" (themes/color :user))
      (check "shadowed pack still has base for unnamed roles" nil (themes/color :assistant)))))

;; -- fallback: missing / malformed / bad hex -> built-in tungsten -------------
(binding [themes/*system-appearance* :dark]
  (let [d (tmp-dir)]
    (spit (java.io.File. d "badhex.toml") "[dark]\nuser = \"not-a-colour\"\n")
    (spit (java.io.File. d "malformed.toml") "[dark\nuser = \"#ffffff\"\n")
    (doseq [name ["missing" "badhex" "malformed"]]
      (binding [themes/*themes-dir* d
                themes/*settings-file* (tmp-file ".json" (str "{\"theme\":\"" name "\"}"))]
        (themes/reload!)
        (check (str "fallback for " name) "#fb923c" (themes/color :user))))))

;; -- appearance: light mode resolves the [light] section ---------------------
(binding [themes/*settings-file* (tmp-file ".json" "{\"appearance\":\"light\"}")
          themes/*themes-dir* (tmp-dir)]
  (themes/reload!)
  (check "light canvas is paper" "#f4f7fb" (themes/color :canvas))
  (check "light user is blue" "#0f5bb5" (themes/color :user)))

;; -- auto follows the forced system appearance --------------------------------
(binding [themes/*settings-file* (tmp-file ".json" "{\"appearance\":\"auto\"}")
          themes/*themes-dir* (tmp-dir)
          themes/*system-appearance* :light]
  (themes/reload!)
  (check "auto resolves light when system says light" "#0f5bb5" (themes/color :user))
  (check "auto light canvas" "#f4f7fb" (themes/color :canvas)))

;; -- available + check -------------------------------------------------------
(binding [themes/*themes-dir* (tmp-dir)]
  (check-true "available includes tungsten" (contains? (themes/available) "tungsten")))

(check "check on missing theme" ["theme not found or invalid"] (themes/check "nope"))

(binding [themes/*settings-file* (tmp-file ".json" "{}")
          themes/*themes-dir* (tmp-dir)
          themes/*system-appearance* :dark]
  (themes/reload!)
  (check-true "check on tungsten returns a vector" (vector? (themes/check "tungsten"))))

;; -- heading bands ------------------------------------------------------------
(binding [themes/*settings-file* (tmp-file ".json" "{}")
          themes/*themes-dir* (tmp-dir)
          themes/*system-appearance* :dark]
  (themes/reload!)
  (check "h1 band is the full base" "#1d374b" (themes/heading-band 1))
  (check "h2 band is one step darker" "#193042" (themes/heading-band 2))
  (check "h6 band is 40% of the base" "#0b161e" (themes/heading-band 6))
  (check-true "h6 band is darker than h1"
              (neg? (compare (themes/heading-band 6) (themes/heading-band 1))))
  (check "level 0 clamps to h1" (themes/heading-band 1) (themes/heading-band 0))
  (check "level 9 clamps to h6" (themes/heading-band 6) (themes/heading-band 9)))

(println "ALL THEMES TESTS PASSED")
(shutdown-agents)
