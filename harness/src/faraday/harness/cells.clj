;; faraday.harness.cells — nodecode-style standing context cells.
;; The chat injects each cell's note line into every system prompt (they ride
;; every request and survive restarts), the agent expands a full body with
;; cell_show, and adds/drops/keeps them through the tools. Storage is one
;; ordered EDN vector at .faraday/cells.edn, written atomically (temp + rename).
(ns faraday.harness.cells
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn cells-file
  [root]
  (str root "/.faraday/cells.edn"))

(defn read-cells
  "The ordered vector of cell maps {:seq :note :body}, or [] when absent.
  A corrupt file degrades to [] rather than breaking the chat."
  [root]
  (try
    (let [f (io/file (cells-file root))]
      (if (.isFile f)
        (let [v (edn/read-string (slurp f :encoding "UTF-8"))]
          (if (vector? v) v []))
        []))
    (catch Exception _ [])))

(defn write-cells!
  "Atomically write `cells` (an ordered vector of cell maps) and return it."
  [root cells]
  (let [f (io/file (cells-file root))]
    (.mkdirs (.getParentFile f))
    (let [tmp (io/file (str (.getPath f) ".tmp." (System/nanoTime)))]
      (spit tmp (pr-str cells) :encoding "UTF-8")
      (.renameTo tmp f)
      cells)))

(defn next-seq
  "The next cell seq after the max present seq (1-based)."
  [cells]
  (inc (reduce max 0 (map :seq cells))))

(defn add-cell!
  "Append a cell with `note` (one-line standing summary) and `body` (full text).
  Returns the added cell map."
  [root note body]
  (let [cells (read-cells root)
        c {:seq (next-seq cells)
           :note (str/trim (str note))
           :body (str body)}]
    (write-cells! root (conj cells c))
    c))

(defn drop-cells!
  "Drop the cells whose seq is in `ids` (a collection of ints). Returns the new
  cells vector."
  [root ids]
  (let [ids (set (map long ids))]
    (write-cells! root (vec (remove #(contains? ids (:seq %)) (read-cells root))))))

(defn keep-cells!
  "Keep only the cells whose seq is in `ids`. Returns the new cells vector."
  [root ids]
  (let [ids (set (map long ids))]
    (write-cells! root (vec (filter #(contains? ids (:seq %)) (read-cells root))))))

(defn cells-lines
  "The standing note lines injected into the system prompt: one \"[seq] note\" per
  cell. Pure over the cells vector."
  [cells]
  (mapv (fn [c] (str "[" (:seq c) "] " (:note c))) cells))

(defn show-cell
  "The full body of the cell with seq `id`, or nil when absent."
  [cells id]
  (some (fn [c] (when (= (long id) (:seq c)) (:body c))) cells))

;; -- tool result shapes (same contract as tools/read-file etc.) --------------
(defn- ok [s] {:status :ok :exit 0 :stdout (str s) :stderr ""})
(defn- err [s] {:status :error :exit 1 :stdout "" :stderr (str s) :error (str s)})

(defn list-tool
  "cells: list the standing context cells as note lines."
  [root _]
  (let [cells (read-cells root)]
    (ok (if (seq cells)
          (str/join "\n" (cells-lines cells))
          "no cells yet — add one with cell_add (a one-line note + the full text)"))))

(defn add-tool
  "cell_add: append a standing context cell. Returns its seq."
  [root args]
  (let [note (str (:note args))
        body (str (:body args))]
    (if (str/blank? note)
      (err "cell_add needs a non-empty :note")
      (let [c (add-cell! root note body)]
        (ok (str "added cell " (:seq c) ": " note))))))

(defn show-tool
  "cell_show: the full body of one cell by seq."
  [root args]
  (if-let [b (show-cell (read-cells root) (long (:id args)))]
    (ok b)
    (err (str "cell_show: no cell " (:id args)))))

(defn drop-tool
  "cell_drop: drop the cells whose seqs are in :ids."
  [root args]
  (let [cells (drop-cells! root (:ids args))]
    (ok (str "dropped; " (count cells) " cell(s) remain"))))

(defn keep-tool
  "cell_keep: keep only the cells whose seqs are in :ids."
  [root args]
  (let [cells (keep-cells! root (:ids args))]
    (ok (str "kept " (count cells) " cell(s)"))))
