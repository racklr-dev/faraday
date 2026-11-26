;; faraday.harness.chat footer test: the pure formatters behind the TUI's
;; bottom status bar — token humanization, home-path shortening, cache-aware
;; usage, context readout, and the joined one-liner. No mounts, no network,
;; no terminal: deterministic.
;; Run: cd harness && jolt run test/faraday/footer_test.clj
(ns jolt.test.footer
  (:require [faraday.harness.chat :as chat]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

;; -- token humanization -------------------------------------------------------
(check "human-tokens under 1000 stays as-is" "999" (chat/human-tokens 999))
(check "human-tokens 1234 is 1.2k" "1.2k" (chat/human-tokens 1234))
(check "human-tokens strips a trailing .0" "2k" (chat/human-tokens 2000))

;; -- home shortening ----------------------------------------------------------
(check "shorten-home abbreviates the user's home"
       (str "~" "/x")
       (chat/shorten-home (str (System/getProperty "user.home") "/x")))
(check "shorten-home leaves a foreign path unchanged"
       "/opt/x"
       (chat/shorten-home "/opt/x"))

;; -- usage + cache ------------------------------------------------------------
(check "usage-readout with a cache read"
       "↑1.2k(310c) ↓480"
       (chat/usage-readout {:prompt_tokens 1234 :completion_tokens 480 :cached-input-tokens 310}))
(check "usage-readout without a cache read"
       "↑1.2k ↓480"
       (chat/usage-readout {:prompt_tokens 1234 :completion_tokens 480}))

;; -- context readout ----------------------------------------------------------
(check "ctx-readout with a window"
       "ctx 24k/64k 38%"
       (chat/ctx-readout {:estimated 24000 :budget 64000}))
(check "ctx-readout unknown window"
       "ctx 24k/?"
       (chat/ctx-readout {:estimated 24000}))
(check "ctx-readout floor only"
       "ctx 24k/≥12k"
       (chat/ctx-readout {:estimated 24000 :floor 12000}))

;; -- the joined footer --------------------------------------------------------
(check "footer-readout joins branch thread cwd usage ctx model"
       "main  #abc12345  /tmp/proj  ↑1.2k(310c) ↓480  ctx 24k/64k 38%  deepseek-flash"
       (chat/footer-readout {:branch "main" :thread-id "abc123456789" :cwd "/tmp/proj"
                             :usage {:prompt_tokens 1234 :completion_tokens 480 :cached-input-tokens 310}
                             :ctx {:estimated 24000 :budget 64000}
                             :model "deepseek-flash"}))

(println "ALL FOOTER TESTS PASSED")
(shutdown-agents)
