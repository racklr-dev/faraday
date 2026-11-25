;; faraday.harness.lev-guard — the optional System One layer behind the
;; deterministic guard. lev (jlt-commons/lev) classifies a command or code
;; string with a calibrated probability in ~100ms on CPU; this namespace owns
;; the faraday tool-danger workflow and the lazy, failure-tolerant agent load.
;;
;; Loading is lazy and OPTIONAL: the encoder checkpoints must be prepared with
;; `jolt prepare` into FARADAY_LEV_DATA (default ~/.config/lev/data). Without
;; them available? is false and every fn returns nil, so faraday keeps running
;; on the regex floor alone.
(ns faraday.harness.lev-guard
  (:require [clojure.string :as str]))

(def questions
  "The tool-danger workflow: typed yes/no questions over {:input text}."
  (array-map
   "destructive"
   (array-map "type" "noul"
              "instructions"
              "Does `input` destroy data, files or state irrecoverably — delete, wipe, format, truncate, or overwrite a device?")
   "mutating"
   (array-map "type" "noul"
              "instructions"
              "Does `input` write, move, remove or otherwise change files or system state?")
   "privileged"
   (array-map "type" "noul"
              "instructions"
              "Does `input` escalate privileges, touch raw devices, or control the host machine?")))

(defn- data-dir []
  (or (System/getenv "FARADAY_LEV_DATA")
      (str (System/getProperty "user.home") "/.config/lev/data")))

(defonce ^:private agent
  (delay
    (try
      (let [load-agent (requiring-resolve 'lev.agent/load-agent)]
        (when (and load-agent (.isDirectory (java.io.File. (data-dir))))
          (load-agent (data-dir))))
      (catch Throwable _ nil))))

(defn available?
  "True when a lev encoder agent is loaded."
  []
  (boolean @agent))

(defn probability
  "The calibrated probability (0..1) that question `q` holds for `text`, or nil
  when lev is unavailable."
  [q text]
  (when-let [a @agent]
    (try
      (let [judge (requiring-resolve 'lev.api/judge)
            q (get questions q)]
        (judge a {"input" (str text)} (get q "instructions")))
      (catch Throwable _ nil))))

(defn verdict
  "A refusal string when lev is confident the input is destructive (always) or
  mutating without a claimed task, else nil. Never throws — nil when lev is
  unavailable or unsure."
  [text claimed?]
  (let [dest (probability "destructive" text)
        mut  (probability "mutating" text)]
    (cond
      (and dest (>= dest 0.8))
      (str "Blocked (lev): destructive — " (format "%.0f%%" (* 100 dest)) " confident.")

      (and mut (>= mut 0.8) (not claimed?))
      (str "Requires a claimed task (lev: mutating — " (format "%.0f%%" (* 100 mut)) " confident).")

      :else nil)))
