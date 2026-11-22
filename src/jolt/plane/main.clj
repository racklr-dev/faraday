;; jolt-plane CLI: boot the plane and run one gate.
;; Usage: jolt run src/jolt/plane/main.clj [plan|check|serve] --config PATH [--request PATH]
(ns jolt.plane.main
  (:require [clojure.data.json :as json]
            [jolt.plane :as plane]))

(defn- fail! [msg]
  (println (str "error: " msg))
  (System/exit 2))

(defn- need [x msg]
  (if x x (fail! msg)))

(defn- load-json [path]
  (json/read-str (slurp path) :key-fn keyword))

(defn- opt
  "Value following flag in args, or default."
  [args flag default]
  (let [v (vec args)
        i (first (keep-indexed (fn [i a] (when (= flag a) i)) v))]
    (if (and i (< (inc i) (count v)))
      (nth v (inc i))
      default)))

(defn- cmd-plan [cfg]
  (let [p (plane/new-plane cfg)]
    (println "plane: budget" (pr-str (:budget (plane/stats p))))
    (println "policy: validated"
             (count (get-in cfg [:policy-set :policies]))
             "policy source(s)")
    (println "stats:" (pr-str (plane/stats p)))
    (plane/close! p)))

(defn- cmd-check [cfg req]
  (let [p (plane/new-plane cfg)
        r (plane/gate p req)]
    (println "decision:" (name (:decision r)))
    (println "permit:" (pr-str (:permit r)))
    (plane/release p (:permit r))
    (plane/close! p)))

(defn- cmd-serve [cfg]
  (let [p (plane/new-plane cfg)]
    (println "plane ready (skeleton): accept loop lands in nc-916C")
    (println "stats:" (pr-str (plane/stats p)))
    (plane/close! p)))

(let [[cmd & rest] *command-line-args*
      args (vec rest)
      cfg (load-json (need (opt args "--config" nil) "missing --config PATH"))]
  (case (or cmd "plan")
    "plan"  (cmd-plan cfg)
    "check" (cmd-check cfg (load-json (need (opt args "--request" nil) "check needs --request PATH")))
    "serve" (cmd-serve cfg)
    (fail! (str "unknown command: " cmd))))
