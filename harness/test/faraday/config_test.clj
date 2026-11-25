;; faraday-harness config test: the settings.json resolver.
;; providers/roles/agents/escalation->orchestrator/proposer/chat + the
;; model-step bridge + validation. Hermetic: temp files, a fake provider
;; builder, no network, and the operator's real key never leaks.
;; Run: cd harness && jolt run test/faraday/config_test.clj
(ns jolt.test.config
  (:require [faraday.harness.config :as c]
            [faraday.agent.model :as model]
            [clojure.data.json :as json]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got] (check label true got))

(defn tmpfile [content]
  (let [f (str (System/getProperty "java.io.tmpdir") "/faraday-config-" (System/nanoTime) ".json")]
    (spit f (if (string? content) content (json/write-str content)))
    f))

(def nf "/nonexistent-faraday-config")

(defn loadc [settings & [auth]]
  (c/load-config {:settings-file (or settings nf)
                  :auth-file (or auth nf)}))

;; -- provider resolution ------------------------------------------------------
(let [cfg (loadc nf)
      p (c/resolve-provider cfg :deepseek {:settings-file nf :auth-file nf})]
  (check "provider default base-url" "https://api.deepseek.com/v1" (:base-url p))
  (check "provider default model" "deepseek-flash" (:model p))
  (check "provider alias" :deepseek (:provider p)))

(let [f (tmpfile {:providers {:deepseek {:base_url "http://srv:9/v1" :model "m" :api_key "k"}}})
      cfg (loadc f)
      p (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf})]
  (check "provider entry base-url" "http://srv:9/v1" (:base-url p))
  (check "provider entry model" "m" (:model p))
  (check "provider entry key" "k" (:api-key p)))

;; -- role resolution ----------------------------------------------------------
(let [f (tmpfile {:providers {:fast {:base_url "u" :model "fast" :api_key "kf"}
                              :strong {:base_url "u" :model "strong" :api_key "ks"}}
                  :roles {:escalate {:provider :strong :reasoning "high"}}})
      cfg (loadc f)
      r (c/resolve-role cfg :escalate {:settings-file f :auth-file nf})]
  (check "role picks its provider alias" :strong (:provider r))
  (check "role keeps provider model" "strong" (:model r))
  (check "role overlays reasoning" "high" (:reasoning r))
  (check "role keeps provider key" "ks" (:api-key r)))

(let [cfg (loadc nf)
      r (c/resolve-role cfg :default {:settings-file nf :auth-file nf})]
  (check "default role resolves" :deepseek (:provider r)))

;; -- agent resolution ---------------------------------------------------------
(let [f (tmpfile {:agents {:planner {:prompt "P" :provider :deepseek
                                     :reasoning "high" :allow_tools ["run_shell"]}}})
      cfg (loadc f)
      a (c/resolve-agent cfg :planner {:settings-file f :auth-file nf})]
  (check "agent prompt" "P" (:prompt a))
  (check "agent reasoning" "high" (:reasoning a))
  (check "agent allow-tools" ["run_shell"] (:allow-tools a))
  (check "agent name" :planner (:name a)))

;; -- escalation ---------------------------------------------------------------
(let [cfg (loadc nf)
      e (c/resolve-escalation cfg)]
  (check "escalation default ladder" [:repair :escalate] (:ladder e))
  (check "escalation default max-repairs" 2 (:max-repairs e))
  (check "escalation default breaker" 3 (:breaker-threshold e)))

;; -- model-step bridge: role-config + providers + ladder ------------------------
(defn fake-builder [log]
  (fn [p]
    (swap! log conj (:provider p))
    (fn [request] {:done "from" :provider (:provider p) :role (:role request)})))

(let [log (atom [])
      f (tmpfile {:providers {:fast {:base_url "u" :model "fast"}
                              :strong {:base_url "u" :model "strong"}}
                  :roles {:default {:provider :fast}
                          :repair {:provider :fast}
                          :escalate {:provider :strong}
                          :orchestrator {:provider :strong :reasoning "max"}}
                  :escalation {:ladder [:repair :escalate :orchestrator]}})
      cfg (loadc f)
      ms (c/model-step-opts cfg {:settings-file f :auth-file nf} (fake-builder log))
      rc (:role-config ms)]
  (check-true "model-step providers include fast" (contains? (:providers ms) :fast))
  (check-true "model-step providers include strong" (contains? (:providers ms) :strong))
  (check "role-config default maps to alias" :fast (:provider (:default rc)))
  (check "role-config orchestrator maps to alias" :strong (:provider (:orchestrator rc)))
  (check "ladder ends at orchestrator" [:repair :escalate :orchestrator]
         (mapv :role (:escalation-ladder ms)))
  (check "ladder repair attempts" 2 (:attempts (nth (:escalation-ladder ms) 0)))
  (check "ladder orchestrator attempts" 1 (:attempts (nth (:escalation-ladder ms) 2)))
  ;; and the whole thing actually runs through model-agent's ladder
  (let [a (model/model-agent ms)
        out (a {:turns 0 :transcript []})]
    (check "model-agent runs from config" {:done "from" :provider :fast :role :fast} out)))

;; -- the headline: a weak loop escalates to the orchestrator (deep thinker) ------
(let [calls (atom [])
      f (tmpfile {:providers {:weak {:base_url "u" :model "weak"}
                              :strong {:base_url "u" :model "strong"}}
                  :roles {:default {:provider :weak}
                          :repair {:provider :weak}
                          :escalate {:provider :weak}
                          :orchestrator {:provider :strong :model "strong" :reasoning "max"}}
                  :escalation {:ladder [:repair :escalate :orchestrator]
                               :max-repairs 1 :max-escalations 1}})
      cfg (loadc f)
      builder (fn [p]
                (let [alias (:provider p)]
                  (fn [request]
                    (swap! calls conj {:alias alias :model (:model request)})
                    (if (= alias :strong)
                      {:done "orchestrated"}
                      {:garbage true}))))
      ms (c/model-step-opts cfg {:settings-file f :auth-file nf} builder)
      a (model/model-agent ms)
      out (a {:turns 0 :transcript []})]
  (check "orchestrator rescues after repair+escalate" {:done "orchestrated"} out)
  (check "escalation call order weak,weak,weak,strong"
         [:weak :weak :weak :strong] (mapv :alias @calls))
  (check "orchestrator rung carries the deep model"
         ["weak" "weak" "weak" "strong"] (mapv :model @calls)))

;; -- no orchestrator configured: ladder stops at escalate -----------------------
(let [log (atom [])
      f (tmpfile {:roles {:default {:provider :deepseek}
                          :repair {:provider :deepseek}
                          :escalate {:provider :deepseek}}})
      cfg (loadc f)
      ms (c/model-step-opts cfg {:settings-file f :auth-file nf} (fake-builder log))
      rc (:role-config ms)]
  (check "no orchestrator role means no orchestrator rung"
         [:repair :escalate] (mapv :role (:escalation-ladder ms))))

;; -- proposer: backward-compatible llm.* shape ----------------------------------
(let [f (tmpfile {:llm {:base_url "http://srv:9/v1" :model "m" :api_key "k-old"}})
      cfg (loadc f)
      p (c/resolve-proposer cfg {:settings-file f :auth-file nf})]
  (check "proposer llm base-url" "http://srv:9/v1" (:base-url p))
  (check "proposer llm model" "m" (:model p))
  (check "proposer llm key" "k-old" (:api-key p)))

(let [f (tmpfile {:proposer {:provider :deepseek :model "deepseek-v4-pro"}})
      cfg (loadc f)
      p (c/resolve-proposer cfg {:settings-file f :auth-file nf})]
  (check "proposer section model wins" "deepseek-v4-pro" (:model p)))

;; -- chat ----------------------------------------------------------------------
(let [f (tmpfile {:chat {:provider :deepseek :model "m2" :system "S" :wrap 70}})
      cfg (loadc f)
      ch (c/resolve-chat cfg {:settings-file f :auth-file nf})]
  (check "chat model" "m2" (:model ch))
  (check "chat system" "S" (:system ch))
  (check "chat wrap" 70 (:wrap ch)))

(let [f (tmpfile {:chat {:context ["you are terse" "prefer clojure"]}})
      cfg (loadc f)
      ch (c/resolve-chat cfg {:settings-file f :auth-file nf})]
  (check "chat context list" ["you are terse" "prefer clojure"] (:context ch)))

(let [f (tmpfile {:context "operator context string"})
      cfg (loadc f)
      ch (c/resolve-chat cfg {:settings-file f :auth-file nf})]
  (check "top-level context string normalizes" ["operator context string"] (:context ch)))

(let [cfg (loadc nf)
      ch (c/resolve-chat cfg {:settings-file nf :auth-file nf})]
  (check "no context is nil" nil (:context ch)))

(let [nc #'c/normalize-context]
  (check "normalize-context string" ["s"] (nc "s"))
  (check "normalize-context drops blanks" ["a" "b"] (nc ["a" "" "b" "  " nil]))
  (check "normalize-context all-blank is nil" nil (nc [""])))

(let [f (tmpfile {:sandbox {:apk_packages ["git" "build-base"] :nix_flake "github:me/proj#dev"}})
      cfg (loadc f)]
  (check "sandbox apk packages resolve" ["git" "build-base"] (:apk-packages (c/resolve-sandbox cfg)))
  (check "sandbox nix flake resolves" "github:me/proj#dev" (:nix-flake (c/resolve-sandbox cfg))))

(let [f (tmpfile {:sandbox {:egress {:allowed_domains ["github.com" "crates.io"]
                                     :allowed_ports [80 443]
                                     :upstream "1.1.1.1"}}})
      cfg (loadc f)
      e (:egress (c/resolve-sandbox cfg))]
  (check "sandbox egress domains resolve" ["github.com" "crates.io"] (:allow-domains e))
  (check "sandbox egress ports resolve" [80 443] (:allow-ports e))
  (check "sandbox egress upstream resolves" "1.1.1.1" (:upstream e)))

(let [f (tmpfile {:sandbox_apk_packages ["curl"]})
      cfg (loadc f)]
  (check "flat sandbox_apk_packages spelling" ["curl"] (:apk-packages (c/resolve-sandbox cfg))))

(let [cfg (loadc nf)]
  (check "no sandbox defaults empty apk" [] (:apk-packages (c/resolve-sandbox cfg)))
  (check "no sandbox nix flake nil" nil (:nix-flake (c/resolve-sandbox cfg))))

;; -- validation ------------------------------------------------------------------
(let [f (tmpfile {:roles {:escalate {:provider :nope}}
                  :escalation {:ladder [:repair :escalate :missing-role]}})
      cfg (loadc f)
      problems (c/validate-config cfg)]
  (check-true "validation names unknown provider"
              (boolean (some #(re-find #"unknown provider" %) problems)))
  (check-true "validation names unknown ladder role"
              (boolean (some #(re-find #"unknown role/agent" %) problems))))

(let [cfg (loadc nf)]
  (check "valid default config has no problems" [] (c/validate-config cfg)))

;; -- token helpers ("!cmd" runs a helper and uses its first stdout line) ----------
(defn check-throws [label thunk]
  (try
    (thunk)
    (println (str "FAIL " label " (no throw)"))
    (System/exit 1)
    (catch Exception _ (println (str "PASS " label)))))

(let [f (tmpfile {:providers {:deepseek {:base_url "u" :model "m" :api_key "!printf 'tk-123'"}}})
      cfg (loadc f)
      p (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf})]
  (check "token helper inline runs command" "tk-123" (:api-key p)))

(let [f (tmpfile {:providers {:deepseek {:base_url "u" :model "m" :api_key "!echo; echo tk-line1"}}})
      cfg (loadc f)
      p (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf})]
  (check "token helper takes first non-blank line" "tk-line1" (:api-key p)))

(let [f (tmpfile {:api_keys {:deepseek {:key "!printf 'tk-from-file'"}}})
      cfg (loadc f)
      p (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf})]
  (check "token helper from settings api_keys file" "tk-from-file" (:api-key p)))

(check-throws "token helper non-zero exit throws"
  (fn [] (let [f (tmpfile {:providers {:deepseek {:base_url "u" :model "m" :api_key "!exit 7"}}})
             cfg (loadc f)]
          (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf}))))

(check-throws "token helper empty output throws"
  (fn [] (let [f (tmpfile {:providers {:deepseek {:base_url "u" :model "m" :api_key "!true"}}})
             cfg (loadc f)]
          (c/resolve-provider cfg :deepseek {:settings-file f :auth-file nf}))))

;; -- volume mounts (compose-style) --------------------------------------------
(let [home (System/getProperty "user.home")]
  (check "mount: default is read-only"
         {:host (str home "/.faraday") :guest "/home/sandbox/.faraday" :readonly? true}
         (c/parse-mount "~/.faraday:/home/sandbox/.faraday"))
  (check "mount: ro explicit"
         {:host (str home "/.faraday") :guest "/g" :readonly? true}
         (c/parse-mount "~/.faraday:/g:ro"))
  (check "mount: rw explicit"
         {:host (str home "/.faraday") :guest "/g" :readonly? false}
         (c/parse-mount "~/.faraday:/g:rw"))
  (check "mount: absolute host stays"
         {:host "/data" :guest "/g" :readonly? true}
         (c/parse-mount "/data:/g"))
  (check "mount: no colon is nil" nil (c/parse-mount "nocolon"))
  (check "mount: blank host is nil" nil (c/parse-mount ":/g")))

(let [cfg {:mounts ["~/.faraday:/home/sandbox/.faraday:ro"
                    "/data:/data:rw"
                    "junk"]}
      ms (c/resolve-mounts cfg)]
  (check "resolve-mounts drops unparsable" 2 (count ms))
  (check "resolve-mounts readonly first" true (:readonly? (first ms)))
  (check "resolve-mounts rw second" false (:readonly? (second ms))))

;; -- project precedence (:project-root / :proj-root) ---------------------------
(let [user (tmpfile {:chat {:wrap 80 :model "user-model"}})
      root (str (System/getProperty "java.io.tmpdir") "/faraday-proj-" (System/nanoTime))
      _ (.mkdirs (java.io.File. (str root "/.faraday")))
      proj (str root "/.faraday/settings.json")
      _ (spit proj (json/write-str {:chat {:wrap 100}}))]
  (check "project-root helper prefers :project-root" root (c/project-root {:project-root root :proj-root "/x"}))
  (check "project-root helper falls back to :proj-root" root (c/project-root {:proj-root root}))
  (let [cfg (c/load-config {:settings-file user :proj-root root})]
    (check "project settings load via :proj-root" 100 (get-in cfg [:chat :wrap]))
    (check "user setting still merges below project" "user-model" (get-in cfg [:chat :model])))
  (let [cfg (c/load-config {:settings-file user :project-root root})]
    (check "project settings load via :project-root" 100 (get-in cfg [:chat :wrap])))
  (let [other (tmpfile {:chat {:wrap 70}})
        cfg (c/load-config {:settings-file user :project-settings-file other})]
    (check "explicit :project-settings-file wins" 70 (get-in cfg [:chat :wrap]))))

(println "ALL CONFIG TESTS PASSED")
(shutdown-agents)
