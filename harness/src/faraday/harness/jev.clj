;; faraday.harness.jev — Jev-style System One classification over the flash
;; model: score candidate label tokens by their log-probabilities in ONE short
;; completion with logprobs. DeepSeek's flash model is a reasoner, so the call
;; asks for a few tokens and scores the answer letter at the first content
;; position where a label is a candidate — the same trick the Jev/NobodyWho
;; post shows in 25 lines.
(ns faraday.harness.jev
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [faraday.harness.llm :as llm]))

(def labels
  "Single-token labels for the tool-danger question, in answer order."
  [{:token "A" :key :safe}
   {:token "B" :key :mutating}
   {:token "C" :key :dangerous}])

(defn- prompt [text]
  (str "Classify this shell command:\n\n" text "\n\n"
       "A. Safe (read-only)\n"
       "B. Mutating (writes, moves or removes files/state)\n"
       "C. Dangerous (destroys data, escalates privileges, touches devices)\n\n"
       "Answer one letter:"))

(defn- token-logprob [toks t]
  (some (fn [x] (when (= (str/trim (str (:token x))) t) (:logprob x))) toks))

(defn probabilities
  "Calibrated probabilities over {:safe :mutating :dangerous} for `text` under
  `cfg`, or nil when the provider returned no logprobs. Softmax over the label
  token log-probabilities."
  [cfg text]
  (try
    (let [body (llm/curl-http (assoc cfg :max-tokens 64 :reasoning "low" :logprobs true :top-logprobs 20)
                              [{:role "system" :content "Answer with exactly one letter: A, B, or C."}
                               {:role "user" :content (prompt (str text))}])
          resp (json/read-str body :key-fn keyword)
          positions (get-in resp [:choices 0 :logprobs :content])
          toks (some (fn [p]
                       (when (some #(token-logprob (:top_logprobs p) (:token %)) labels)
                         (:top_logprobs p)))
                     positions)]
      (when (seq toks)
        (let [lps (mapv (fn [{:keys [token]}] (or (token-logprob toks token) -1e9)) labels)
              mx (apply max lps)
              exps (mapv #(Math/exp (- % mx)) lps)
              z (reduce + exps)]
          (into {}
                (map-indexed (fn [i {:keys [key]}] [key (/ (nth exps i) z)]) labels)))))
    (catch Throwable _ nil)))
