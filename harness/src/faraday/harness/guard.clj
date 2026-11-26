;; faraday.harness.guard — declarative tool-call policy, composed like
;; Claude's permission rules / dirge's guardrails: an ordered table of shell
;; command patterns, each {:re :why :kind}. :kind :reject refuses always
;; (destructive/privileged commands are never allowed); :kind :mutating refuses
;; only when no task is claimed (in_progress) in the transcript. First match
;; wins, and the reason goes back to the model as the refusal, so the model
;; learns the rule at the moment it applies.
(ns faraday.harness.guard
  (:require [clojure.string :as str]))

(def ^:private shell-rules
  "Ordered shell command rules. Reject rules come first."
  [{:re #"\bgit\s+add\s+(-A|--all)\b"
    :why "git add -A stages everything — stage explicit paths"
    :kind :reject}
   {:re #"\brm\s+-rf\s+(/|\*|~)"
    :why "rm -rf on a root/glob/home path"
    :kind :reject}
   {:re #"\bdd\b"
    :why "dd writes raw devices"
    :kind :reject}
   {:re #"\bshred\b"
    :why "shred destroys data irrecoverably"
    :kind :reject}
   {:re #"\b(umount|mount)\b"
    :why "mount/umount changes filesystem state"
    :kind :reject}
   {:re #"\bmkfs\b"
    :why "mkfs formats a filesystem"
    :kind :reject}
   {:re #"\b(fdisk|parted|sfdisk)\b"
    :why "partition-table edits"
    :kind :reject}
   {:re #"\b(reboot|shutdown|poweroff)\b"
    :why "host power control"
    :kind :reject}
   {:re #"\bsudo\b"
    :why "privilege escalation"
    :kind :reject}
   {:re #"\bcurl\b[^|]*\|\s*(ba)?sh"
    :why "curl-pipe-to-shell"
    :kind :reject}
   {:re #"\b(chmod|chown)\s+-R\s+/"
    :why "recursive permission/ownership change on a root path"
    :kind :reject}

   ;; mutating — allowed only while a task is claimed (in_progress)
   {:re #"sed\s+(-i|--in-place)\b"
    :why "in-place edit (sed -i)"
    :kind :mutating}
   {:re #"\b(mv|cp|rm|tee|touch|mkdir|rmdir|install|ln)\s"
    :why "file mutation"
    :kind :mutating}
   {:re #"\bgit\s+(add|commit|push|mv|rm)\b"
    :why "git mutation"
    :kind :mutating}
   {:re #"\bperl\s+-pi\b|\bsponge\b"
    :why "in-place file edit"
    :kind :mutating}
   {:re #">>|\s>\s*[^/&][^&\s]*"
    :why "write redirect"
    :kind :mutating}])

(defn- command [argv]
  (str/join " " (map str argv)))

(defn claimed?
  "True when the transcript shows a task moved to in_progress."
  [entries]
  (boolean
   (some (fn [e]
           (and (= :tool-call (:role e))
                (= "task_set_status" (:name e))
                (= "in_progress" (:status (:args e)))))
         entries)))

(defn- shell-refusal
  "The first shell rule argv trips, as a refusal string, or nil."
  [argv claimed?]
  (let [cmd (command argv)]
    (some
     (fn [{:keys [re why kind]}]
       (when (re-find re cmd)
         (case kind
           :reject (str "Blocked: " why ".")
           :mutating (when-not claimed?
                       (str why " — but no task is claimed; claim one first (task_set_status <id> in_progress)."))
           nil)))
     shell-rules)))

(defn verdict
  "The refusal for a tool call (nil = allowed). :entries is the transcript so
  far, used only to test the claimed-task condition."
  [{:keys [name args] :as tc} entries]
  (let [claimed? (claimed? entries)]
    (cond
      (= "task_set_status" name)
      (when (and (not claimed?) (not= "in_progress" (:status args)))
        "No task is claimed — claim one first (task_set_status <id> in_progress) before changing task status.")

      (contains? #{"run_shell" "shell" "exec"} name)
      (shell-refusal (:argv args) claimed?)

      :else nil)))
