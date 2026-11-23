;; faraday.contracts test: the six SPEC.md shapes round-trip through their
;; canonical examples, refusals name the first bad member, and the schema
;; projection advertises the declaration.
;; Run: cd contracts && jolt run test/faraday/contracts_test.clj
(ns jolt.test.contracts
  (:require [faraday.contracts :as c]
            [clojure.data.json :as json]))

(defn check [label expected got]
  (if (= expected got)
    (println (str "PASS " label))
    (do (println (str "FAIL " label))
        (println (str "  expected " (pr-str expected)))
        (println (str "  got      " (pr-str got)))
        (System/exit 1))))

(defn check-true [label got]
  (check label true got))

(defn read-example [file]
  (json/read-str (slurp file)))

(def examples
  [[:thread-message "examples/thread-message.json"]
   [:sandbox-request "examples/sandbox-request.json"]
   [:tool-invocation "examples/tool-invocation.json"]
   [:tool-result "examples/tool-result.json"]
   [:egress-contract "examples/egress-contract.json"]
   [:budget-permit "examples/budget-permit.json"]
   [:policy-set "examples/policy-set.json"]])

;; -- canonical examples round-trip decode -> encode --------------------------
(doseq [[contract file] examples]
  (let [obj (read-example file)
        rec (c/decode contract obj)]
    (check-true (str contract " example decodes") (not (c/refusal? rec)))
    (check (str contract " example encodes back") obj (c/encode contract rec))
    (check (str contract " decode-encode-decode stable")
           rec (c/decode contract (c/encode contract rec)))))

;; -- SPEC values decoded -----------------------------------------------------
(let [rec (c/decode :thread-message (read-example "examples/thread-message.json"))]
  (check "thread-message thread-id" "thr_01J8VK2Q" (:thread-id rec))
  (check "thread-message parent-id null" nil (:parent-id rec))
  (check "thread-message kind" "spawn" (:kind rec))
  (check "thread-message seq" 0 (:seq rec)))

(let [rec (c/decode :sandbox-request (read-example "examples/sandbox-request.json"))]
  (check "sandbox argv" ["/bin/sh" "-c" "echo ok"] (:argv rec))
  (check "sandbox mounts decoded" [{:host-path "/data" :tag "data" :readonly false}] (:mounts rec))
  (check "sandbox cpu" 1 (:cpu rec))
  (check "sandbox stdin null" nil (:stdin rec)))

(let [rec (c/decode :tool-result (read-example "examples/tool-result.json"))]
  (check "tool-result segment" [{:type "text" :content "total 4"}] (:segments rec))
  (check "tool-result exit-code" 0 (:exit-code rec))
  (check "tool-result error null" nil (:error rec)))

(let [rec (c/decode :budget-permit (read-example "examples/budget-permit.json"))]
  (check "budget cost decoded" {:cpu 1 :mem-mb 512} (:cost rec)))

;; -- in-process keyword maps decode identically ------------------------------
(let [obj (read-example "examples/thread-message.json")]
  (check "keyword map equals JSON decode"
         (c/decode :thread-message obj)
         (c/decode :thread-message
                   {:v 1 :thread-id "thr_01J8VK2Q" :parent-id nil :msg-id "msg_01J8VK2Q"
                    :seq 0 :kind "spawn" :ts "2026-11-22T07:24:19.907Z" :payload {}})))

;; -- refusals name the first bad member --------------------------------------
(let [r (c/decode :thread-message
                  {:thread-id "t" :parent-id nil :msg-id "m" :kind "spawn" :ts "now" :payload {}})]
  (check-true "refusal for missing required" (c/refusal? r))
  (check "refusal path names v" ["v"] (:path r)))

(let [r (c/decode :thread-message
                  {:v 1 :thread-id "t" :parent-id nil :msg-id "m" :seq 0
                   :kind "bogus" :ts "now" :payload {}})]
  (check-true "refusal for bad enum" (c/refusal? r))
  (check "bad enum path names kind" ["kind"] (:path r)))

(let [r (c/decode :sandbox-request {:v 1 :request-id "r" :image "img" :argv "not-a-vector"})]
  (check-true "refusal for wrong type" (c/refusal? r))
  (check "wrong type path names argv" ["argv"] (:path r)))

(let [r (c/decode :sandbox-request {:v 1 :request-id "r" :image "img" :argv []})]
  (check-true "refusal for empty argv" (c/refusal? r)))

;; -- absent vs null preserved -------------------------------------------------
(let [rec (c/decode :sandbox-request {:v 1 :request-id "r" :image "img" :argv ["a"]})]
  (check "stdin absent" false (contains? rec :stdin))
  (check "cpu defaulted" 1 (:cpu rec))
  (check "no mem-mb default" false (contains? rec :mem-mb)))

;; -- schema projection --------------------------------------------------------
(let [s (c/schema :thread-message)]
  (check "schema type" "object" (:type s))
  (check-true "schema requires thread-id" (boolean (some #{"thread-id"} (:required s))))
  (check "schema thread-id type" "string" (get-in s [:properties "thread-id" :type])))

(let [s (c/schema :sandbox-request)]
  (check "schema mounts items object" "object" (get-in s [:properties "mounts" :items :type])))

(let [names (set (map name (keys (c/contracts))))]
  (doseq [n ["thread-message" "sandbox-request" "tool-invocation" "tool-result"
             "egress-contract" "budget-permit" "policy-set"]]
    (check-true (str "registered " n) (contains? names n))))

(println "ALL CONTRACTS TESTS PASSED")
