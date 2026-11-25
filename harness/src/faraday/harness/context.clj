;; faraday.harness.context — context-window + cache-usage measurement, ported
;; from nodecode's context engine (src/engine/context.lisp + provider.lisp).
;; Estimates are JSON chars/4; each round's measured prompt over its estimate
;; calibrates the (provider, model) ratio so the next estimate converges to
;; what the server actually counted. The window budget is a learned belief:
;; successful rounds prove a floor, hard context rejections bound a ceiling.
;; Pressure is :unknown/:ok/:pressure against that budget at the 0.85 trigger.
;; Pure functions plus two in-image atoms (calibration, window observations);
;; every fn takes an optional state atom first so tests stay hermetic.
(ns faraday.harness.context
  (:require [clojure.data.json :as json]))

(def trigger-ratio
  "Whole-turn eviction trigger: start when the estimated context reaches this
  share of the window (nodecode *context-eviction-trigger-ratio*)."
  0.85)

(def calibration
  "\"provider/model\" -> the newest round's measured prompt tokens over its
  estimated tokens (nodecode *context-estimate-calibration*). In-image only:
  re-measured every round, never durable state."
  (atom {}))

(def windows
  "\"provider/model\" -> {:floor n :ceiling n} context-window evidence
  (nodecode context_window_observations). Floor: the largest prompt the server
  actually processed. Ceiling: the estimate-grade of a refused request."
  (atom {}))

(defn estimate-json-tokens
  "nodecode ESTIMATE-JSON-TOKENS: chars/4 of JSON text, the cheap estimate the
  calibration ratio corrects."
  [x]
  (max 0 (long (Math/ceil (/ (count (json/write-str x)) 4.0)))))

(defn estimate-messages-tokens
  "The estimated prompt tokens for MESSAGES (a chat message vector): chars/4 of
  the whole array's JSON text."
  [messages]
  (estimate-json-tokens messages))

;; -- calibration -------------------------------------------------------------

(defn context-estimate-ratio
  "The measured/estimated ratio for (PROVIDER, MODEL), 1.0 until observed."
  ([provider model] (context-estimate-ratio calibration provider model))
  ([state provider model]
   (or (get @state (str provider "/" model)) 1.0)))

(defn observe-context-estimate!
  "Take one round's MEASURED prompt tokens over its ESTIMATED count as the
  (PROVIDER, MODEL) ratio, replacing the last. No-op unless provider/model are
  strings and both counts are positive integers."
  ([provider model estimated measured]
   (observe-context-estimate! calibration provider model estimated measured))
  ([state provider model estimated measured]
   (when (and (string? provider) (string? model)
              (integer? estimated) (pos? estimated)
              (integer? measured) (pos? measured))
     (swap! state assoc (str provider "/" model) (/ (double measured) estimated)))
   nil))

;; -- learned budget ----------------------------------------------------------

(defn context-window-belief
  "The effective window for (PROVIDER, MODEL): OPERATOR-P returns the operator's
  explicit PRIOR verbatim; otherwise the prior is corrected by durable evidence
  — a proven floor raises it, a rejection ceiling caps it, and a floor outranks
  a ceiling it contradicts (floors are measured, ceilings are estimates). A nil
  prior stays nil until a ceiling contributes a bound."
  ([provider model prior operator-p]
   (context-window-belief windows provider model prior operator-p))
  ([state provider model prior operator-p]
   (if (or operator-p (not (string? provider)) (not (string? model)))
     prior
     (let [{:keys [floor ceiling]} (get @state (str provider "/" model))]
       (cond
         (nil? prior) (when ceiling (max (or floor 0) ceiling))
         ceiling (max (or floor 0) (min (max prior (or floor 0)) ceiling))
         :else (max prior (or floor 0)))))))

(defn observe-context-floor!
  "Record a proven floor: the server actually processed PROMPT-TOKENS, so the
  window is at least that large. Only raises the stored floor."
  ([provider model prompt-tokens]
   (observe-context-floor! windows provider model prompt-tokens))
  ([state provider model prompt-tokens]
   (when (and (string? provider) (string? model)
              (integer? prompt-tokens) (pos? prompt-tokens))
     (swap! state update (str provider "/" model)
            (fn [obs] (assoc obs :floor (max (:floor obs 0) prompt-tokens)))))
   nil))

(defn observe-context-ceiling!
  "Record a ceiling: a request of ESTIMATED tokens was refused as over the
  window, so the window is at most that large. Only lowers the stored ceiling."
  ([provider model estimated]
   (observe-context-ceiling! windows provider model estimated))
  ([state provider model estimated]
   (when (and (string? provider) (string? model)
              (integer? estimated) (pos? estimated))
     (swap! state update (str provider "/" model)
            (fn [obs] (assoc obs :ceiling (min (:ceiling obs estimated) estimated)))))
   nil))

(defn effective-context-budget
  "CFG's context budget through the learned-window belief. CFG carries
  :provider, :model, :context-tokens (the prior window) and
  :context-tokens-operator-p (true when the prior is explicit operator config)."
  ([cfg] (effective-context-budget windows cfg))
  ([state cfg]
   (context-window-belief state (:provider cfg) (:model cfg)
                          (:context-tokens cfg)
                          (:context-tokens-operator-p cfg))))

;; -- pressure ----------------------------------------------------------------

(defn context-pressure-decision
  "Classify one request estimate without inventing a provider window: :unknown
  (no budget), :ok, or :pressure (estimate past the 0.85 trigger). A nil BUDGET
  is deliberately unknown — never evict merely because the estimate is large."
  [estimated budget]
  (let [estimate (max 0 (or estimated 0))]
    {:status (cond
               (not (and (number? budget) (pos? budget))) :unknown
               (> estimate (* budget trigger-ratio)) :pressure
               :else :ok)
     :estimated estimate
     :budget budget
     :trigger (when (and (number? budget) (pos? budget)) (* budget trigger-ratio))}))

;; -- readout -----------------------------------------------------------------

(defn context-estimate
  "nodecode SESSION-CONTEXT-ESTIMATE reduced to faraday's message model: the
  estimated prompt tokens CFG+MESSAGES' next round would carry, corrected by
  the calibration ratio, split into :standing (system prompt — what no eviction
  reaches) and :history (the evictable conversation), with :budget/:status/
  :trigger from the learned window."
  ([cfg messages] (context-estimate calibration windows cfg messages))
  ([cal-state win-state cfg messages]
   (let [messages (vec (or messages []))
         standing (into [] (filter #(= "system" (:role %))) messages)
         history (into [] (remove #(= "system" (:role %))) messages)
         raw (estimate-messages-tokens messages)
         ratio (context-estimate-ratio cal-state (:provider cfg) (:model cfg))
         estimated (long (Math/round (* ratio raw)))
         budget (effective-context-budget win-state cfg)
         pressure (context-pressure-decision estimated budget)
         scaled (fn [x] (long (Math/round (* ratio (estimate-json-tokens x)))))]
     (assoc pressure
            :ratio ratio
            :raw raw
            :parts {:messages raw
                    :standing (scaled standing)
                    :history (scaled history)}))))