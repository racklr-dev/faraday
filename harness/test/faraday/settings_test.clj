;; faraday-harness settings test: llm-opts precedence, file fallbacks, defaults.
;; Hermetic: temp files + explicit :settings-file/:auth-file, so the operator's
;; real ~/.faraday and ~/.nodecode never leak into assertions.
;; Run: cd harness && jolt run test/faraday/settings_test.clj
(ns jolt.test.settings
  (:require [faraday.harness.settings :as s]
            [clojure.data.json :as json]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn no-key-env? []
  (every? nil? [(System/getenv "FARADAY_LLM_API_KEY")
                (System/getenv "FARADAY_LLM_KEY")
                (System/getenv "DEEPSEEK_API_KEY")]))

(defn tmpfile [content]
  (let [f (str (System/getProperty "java.io.tmpdir") "/faraday-settings-" (System/nanoTime) ".json")]
    (spit f (if (string? content) content (json/write-str content)))
    f))

;; defaults with no files present
(let [nf "/nonexistent-faraday-settings"
      cfg (s/llm-opts {:settings-file nf :auth-file nf})]
  (check "default base-url" "https://api.deepseek.com/v1" (:base-url cfg))
  (check "default model" "deepseek-flash" (:model cfg))
  (when (no-key-env?)
    (check "no key without files or env" nil (:api-key cfg))))

;; faraday-native settings shape provides key + model + base-url
(let [f (tmpfile {:llm {:base_url "http://srv:9/v1" :model "m" :api_key "k-from-settings"}})
      cfg (s/llm-opts {:settings-file f :auth-file "/nonexistent"})]
  (check "settings base-url" "http://srv:9/v1" (:base-url cfg))
  (check "settings model" "m" (:model cfg))
  (check "settings key" "k-from-settings" (:api-key cfg)))

;; settings.json in auth.json shape (what `cp ~/.nodecode/auth.json` produces)
(let [f (tmpfile {:api_keys {:deepseek {:key "k-auth-shape"}}})
      cfg (s/llm-opts {:settings-file f :auth-file "/nonexistent"})]
  (check "auth-shape settings key" "k-auth-shape" (:api-key cfg)))

;; nodecode auth.json fallback when settings has no key
(let [sf (tmpfile {:llm {:model "m"}})
      af (tmpfile {:api_keys {:deepseek {:key "k-nodecode"}}})
      cfg (s/llm-opts {:settings-file sf :auth-file af})]
  (check "auth fallback key" "k-nodecode" (:api-key cfg))
  (check "settings model survives" "m" (:model cfg)))

;; opts win over everything
(let [sf (tmpfile {:llm {:model "file-m" :api_key "file-k"}})
      cfg (s/llm-opts {:settings-file sf :auth-file "/nonexistent"
                       :model "opt-m" :api-key "opt-k"})]
  (check "opts model wins" "opt-m" (:model cfg))
  (check "opts key wins" "opt-k" (:api-key cfg)))

(println "ALL SETTINGS TESTS PASSED")
