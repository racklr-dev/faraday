;; faraday-harness sandbox functional test: the chat's run_shell tool executors
;; for real. host-exec always runs (no privileges needed); the sandbox executor
;; (jolt.sandbox warm alpine VM) runs only when /dev/kvm is openable and the
;; libkrun 2.0 artifacts are present, else it skips so CI stays green.
;; Run (real KVM): cd harness && sudo -u jm --preserve-env jolt run test/faraday/sandbox_functional_test.clj
(ns jolt.test.sandbox-functional
  (:require [faraday.harness.chat :as chat]
            [clojure.string :as str]
            [glimmer.core :as ui]
            [glimmer.ratom :as r]
            [glimmer-tui.core :as tui]
            [glimmer-tui.screen :as scr]
            [glimmer-tui.widget :as w])
  (:import [java.io File RandomAccessFile]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn kvm-openable? []
  (try (with-open [_ (RandomAccessFile. "/dev/kvm" "rw")] true)
       (catch Exception _ false)))

(defn libkrun-present? []
  (let [dir (or (System/getenv "JOLT_LIBKRUN_DIR") "/tmp/libkrun/target/release")]
    (and (.exists (File. (str dir "/krun-init")))
         (or (.exists (File. (str dir "/libkrun.so")))
             (.exists (File. (str dir "/libkrun.so.2")))))))

;; -- host-exec (always runs, hermetic) ---------------------------------------
(let [exec chat/host-exec]
  (check "host-exec: echo ok"
         {:status :ok :exit 0 :stdout "host-ok\n"}
         (select-keys (exec "run_shell" {:argv ["echo" "host-ok"]}) [:status :exit :stdout]))
  (check "host-exec: nonzero exit"
         {:status :error :exit 3}
         (select-keys (exec "run_shell" {:argv ["sh" "-c" "exit 3"]}) [:status :exit]))
  (check "host-exec: unknown tool"
         :error
         (:status (exec "nope" {:argv ["echo" "x"]})))
  (check "host-exec: empty argv"
         :error
         (:status (exec "run_shell" {:argv []}))))

;; -- sandbox exec (real warm jolt-sandbox VM) --------------------------------
(if-not (and (kvm-openable?) (libkrun-present?))
  (println "SKIP sandbox: no /dev/kvm access or libkrun 2.0 artifacts missing")
  (let [ws "/tmp/faraday-sandbox-func-ws"
        _ (.mkdirs (File. ws))
        cfg-dir "/tmp/faraday-sandbox-func-cfg"
        _ (.mkdirs (File. cfg-dir))
        _ (spit (str cfg-dir "/settings.json") "{\"answer\": 42}\n")
        project-dir (or (System/getenv "FARADAY_HARNESS_DIR") (System/getProperty "user.dir"))
        sb (chat/sandbox-exec-factory {:workspace ws :project-dir project-dir
                                       :mounts [{:host cfg-dir :guest "/home/sandbox/.faraday" :readonly? true}]})
        exec (:exec sb)]
    (try
      (check "sandbox: echo ok"
             {:status :ok :exit 0 :stdout "sandbox-ok\n"}
             (select-keys (exec "run_shell" {:argv ["echo" "sandbox-ok"]}) [:status :exit :stdout]))
      (check "sandbox: pwd is the host cwd path"
             {:status :ok :exit 0 :stdout (str ws "\n")}
             (select-keys (exec "run_shell" {:argv ["pwd"]}) [:status :exit :stdout]))
      (check "sandbox: extra mount readable in-guest"
             {:status :ok :exit 0 :stdout "{\"answer\": 42}\n"}
             (select-keys (exec "run_shell" {:argv ["cat" "/home/sandbox/.faraday/settings.json"]}) [:status :exit :stdout]))
      (check "sandbox: read-only mount rejects writes"
             :error
             (:status (exec "run_shell" {:argv ["touch" "/home/sandbox/.faraday/nope.txt"]})))
      (check "sandbox: argv quoting (space in one arg)"
             {:status :ok :exit 0 :stdout "hello world\n"}
             (select-keys (exec "run_shell" {:argv ["echo" "hello world"]}) [:status :exit :stdout]))
      (check "sandbox: nonzero exit"
             {:status :error :exit 4}
             (select-keys (exec "run_shell" {:argv ["sh" "-c" "exit 4"]}) [:status :exit]))
      (check "sandbox: unknown tool"
             :error
             (:status (exec "nope" {:argv ["echo" "x"]})))
      (check "sandbox: empty argv"
             :error
             (:status (exec "run_shell" {:argv []})))

      ;; the agentic tool loop driven end-to-end with the REAL sandbox exec:
      ;; model emits a tool-call, the command runs in the VM, its output comes
      ;; back as a tool-result turn, then the model answers.
      (let [calls (atom [])
            fake-call (fn [msgs]
                        (swap! calls conj (mapv :role msgs))
                        (if (= 1 (count @calls))
                          "{:tool-call {:name \"run_shell\" :args {:argv [\"echo\" \"sandbox-loop-ok\"]}}}"
                          "{:done \"loop answer\"}"))
            state (r/atom {:messages []})
            entries [{:role :user :text "run it"}]]
        (chat/run-turn! {:call fake-call :exec exec :system "S" :max-turns 8} state entries)
        (let [msgs (:messages @state)
              tr (last (filter #(= :tool-result (:role %)) msgs))]
          (check "loop: two model calls" 2 (count @calls))
          (check "loop: tool ran in the real sandbox"
                 {:status :ok :exit 0 :text "sandbox-loop-ok\n"}
                 (select-keys tr [:status :exit :text]))
          (check "loop: assistant answered" "loop answer" (-> msgs last :text))))

      ;; the chat component itself mounted headlessly, driven with the REAL
      ;; sandbox exec: type a message, press Enter, and the transcript must
      ;; show the tool call, the VM's real stdout, and the final answer.
      (let [calls (atom [])
            fake-call (fn [msgs]
                        (swap! calls conj (mapv :role msgs))
                        (if (= 1 (count @calls))
                          "{:tool-call {:name \"run_shell\" :args {:argv [\"echo\" \"chat-app-sandbox-ok\"]}}}"
                          "{:done \"chat app answer\"}"))
            app (chat/chat-app {:call fake-call :exec exec :wrap 40})
            root (w/node :window {})
            screen (scr/buffer-screen 60 20)]
        (tui/attach! root screen)
        (ui/mount root :window [app])
        (tui/frame!)
        (tui/press! 114) ; r
        (tui/press! 117) ; u
        (tui/press! 110) ; n
        (tui/press! 10)  ; Enter
        (Thread/sleep 150)
        (tui/drain!)
        (tui/frame!)
        (let [text (apply str (scr/lines screen))]
          (check "chat-app: tool call shown" true (str/includes? text "→"))
          (check "chat-app: real VM stdout in transcript" true (str/includes? text "chat-app-sandbox-ok"))
          (check "chat-app: final answer shown" true (str/includes? text "chat app answer"))
          (check "chat-app: two model calls" 2 (count @calls))))
      (finally ((:close sb))))))

(println "sandbox functional test done")
(shutdown-agents)
