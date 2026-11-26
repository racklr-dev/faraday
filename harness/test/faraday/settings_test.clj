;; faraday.harness.settings test: the /settings editor surface — flattening a
;; config map to dialog rows, secret masking, path edits, coercion, and write-
;; back to the user settings file. Run: cd harness && jolt run test/faraday/settings_test.clj
(ns jolt.test.settings
  (:require [faraday.harness.settings :as s]
            [faraday.harness.config :as config]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- flattening ----------------------------------------------------------------
(let [m {:providers {:deepseek {:base-url "https://api.deepseek.com/v1"
                                :api-key "sk-secret"
                                :reasoning "med"}}
         :chat {:wrap 80 :quiet false}
         :escalation {:ladder ["repair" "escalate"]}}
      rows (s/rows-for-map m)]
  (check "section header for a map"
          true (some #(and (= :section (:kind %)) (= "providers" (:label %)) (= "(1)" (:value %))) rows))
  (check "nested section for a sub-map"
          true (some #(and (= :section (:kind %)) (= "deepseek" (:label %))) rows))
  (check "boolean becomes a toggle with checked?"
          true (some #(and (= :toggle (:kind %)) (false? (:checked? %)) (= [:chat :quiet] (:path %))) rows))
  (check "number becomes a value row"
          true (some #(and (= :value (:kind %)) (= [:chat :wrap] (:path %)) (= "80" (:value %))) rows))
  (check "string becomes a value row"
          true (some #(and (= :value (:kind %)) (= [:providers :deepseek :base-url] (:path %))) rows))
  (check "api-key is masked"
          true (some #(and (:masked? %) (= s/masked (:value %)) (= [:providers :deepseek :api-key] (:path %))) rows))
  (check "masked row never carries :raw"
          true (every? (fn [r] (not (and (:masked? r) (contains? r :raw)))) rows))
  (check "vector becomes a read-only row"
          true (some #(and (:read-only? %) (= [:escalation :ladder] (:path %))
                           (str/includes? (:value %) "repair")) rows))
  (check "leaf carries a dotted desc"
          true (some #(= "providers.deepseek.base-url" (:desc %)) rows))
  (check "leaf is indented by its depth"
          true (some #(and (= [:providers :deepseek :base-url] (:path %)) (= 2 (:depth %))) rows)))

;; -- coercion + edits -----------------------------------------------------------
(check "coerce :num parses a long" 80 (s/coerce :num "80"))
(check "coerce :num parses a double" 0.4 (s/coerce :num "0.4"))
(check "coerce :num falls back to the string" "x" (s/coerce :num "x"))
(check "coerce :bool yes" true (s/coerce :bool "yes"))
(check "coerce :bool off" false (s/coerce :bool "nope"))
(check "coerce :str stays a string" "hi" (s/coerce :str "hi"))

(check "set-value assoc-in with coercion" {:a {:b 5}}
       (s/set-value-at-path {:a {}} [:a :b] "5" :num))
(check "set-value blank leaves the map unchanged" {:a {}}
       (s/set-value-at-path {:a {}} [:a :b] "" :str))
(check "set-value string" {:x "y"}
       (s/set-value-at-path {} [:x] "y" :str))
(check "toggle flips a boolean at a path" {:a {:b true}}
       (s/toggle-at-path {:a {:b false}} [:a :b]))

;; -- persistence ------------------------------------------------------------------
(let [tmp (str (System/getProperty "java.io.tmpdir") "/faraday-settings-test-" (System/nanoTime) ".json")]
  (with-redefs [faraday.harness.config/settings-file (fn [] tmp)]
    (check "write-settings! returns the path" tmp
           (s/write-settings! {:chat {:wrap 90}}))
    (check "write-settings! wrote pretty JSON" {:chat {:wrap 90}}
           (json/read-str (slurp tmp) :key-fn keyword))
    (spit tmp "{\"sentinel\":1}")
    (check "scaffold! never overwrites an existing file" "{\"sentinel\":1}" (slurp tmp))
    (check "scaffold! returns the path" tmp (s/scaffold!)))
  (let [tmp2 (str (System/getProperty "java.io.tmpdir") "/faraday-settings-scaffold-" (System/nanoTime) ".json")]
    (with-redefs [faraday.harness.config/settings-file (fn [] tmp2)]
      (s/scaffold!)
      (check "scaffold! writes a starter file" true (str/includes? (slurp tmp2) "escalation")))))

;; -- project-layer delta writes + edit target ----------------------------------
(let [tmp (str (System/getProperty "java.io.tmpdir") "/faraday-settings-leaf-" (System/nanoTime) ".json")
      _ (spit tmp "{\"chat\":{\"wrap\":80},\"providers\":{\"deepseek\":{\"api_key\":\"sk\"}}}")
      _ (s/write-leaf! tmp [:chat :wrap] 90)
      m (json/read-str (slurp tmp) :key-fn keyword)]
  (check "write-leaf! changes only the edited leaf" 90 (get-in m [:chat :wrap]))
  (check "write-leaf! preserves the rest of the file" "sk" (get-in m [:providers :deepseek :api-key])))

(let [tmp (str (System/getProperty "java.io.tmpdir") "/faraday-settings-leaf-new-" (System/nanoTime) ".json")
      _ (s/write-leaf! tmp [:chat :wrap] 70)
      m (json/read-str (slurp tmp) :key-fn keyword)]
  (check "write-leaf! creates a missing file" 70 (get-in m [:chat :wrap])))

(let [user (str (System/getProperty "java.io.tmpdir") "/faraday-settings-user-" (System/nanoTime) ".json")]
  (check "edit-target-file picks the user file outside a project"
          {:path user :project? false} (s/edit-target-file {:settings-file user})))

(let [root (str (System/getProperty "java.io.tmpdir") "/faraday-settings-proj-" (System/nanoTime))
      _ (.mkdirs (java.io.File. (str root "/.faraday")))
      _ (spit (str root "/.faraday/harness.db") "x")
      user (str (System/getProperty "java.io.tmpdir") "/faraday-settings-user-" (System/nanoTime) ".json")]
  (check "edit-target-file picks the project file for a real project"
          {:path (str root "/.faraday/settings.json") :project? true}
          (s/edit-target-file {:settings-file user :proj-root root})))

(let [root (str (System/getProperty "java.io.tmpdir") "/faraday-settings-plain-" (System/nanoTime))
      _ (.mkdirs (java.io.File. root))
      user (str (System/getProperty "java.io.tmpdir") "/faraday-settings-user-" (System/nanoTime) ".json")]
  (check "edit-target-file stays on the user file when the root is not a project"
          {:path user :project? false}
          (s/edit-target-file {:settings-file user :proj-root root})))

(let [root (str (System/getProperty "java.io.tmpdir") "/faraday-settings-fileonly-" (System/nanoTime))
      _ (.mkdirs (java.io.File. (str root "/.faraday")))
      _ (spit (str root "/.faraday/settings.json") "{\"chat\":{\"wrap\":80}}")
      user (str (System/getProperty "java.io.tmpdir") "/faraday-settings-user-" (System/nanoTime) ".json")]
  (check "edit-target-file picks the project file when its settings file already exists"
          {:path (str root "/.faraday/settings.json") :project? true}
          (s/edit-target-file {:settings-file user :proj-root root})))

(println "ALL SETTINGS TESTS PASSED")
