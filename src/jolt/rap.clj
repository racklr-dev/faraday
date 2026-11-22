;; jolt-rap: RAP tool server + MCP adapter.
;; Scaffold stub: the public API surface is pinned, every body throws :not-implemented.
(ns jolt.rap
  "RAP tool server and MCP adapter for the jolt control plane.

  Runs Tool Invocations (jolt-contracts SPEC.md §3) against a jolt-vm-pool,
  streams results to subscribers, and adapts them to MCP. Each invocation has
  exactly one terminal result, keyed by call-id."
  (:refer-clojure :exclude [close]))

(defn new-server
  "Open a tool server over opts, a map with :vm-pool (jolt-vm-pool handle),
  :fanout (jolt-fanout runtime), and optionally :policy-hook (fn call -> truthy
  to permit, nil = always permit). Returns an opaque server handle."
  [opts]
  (throw (ex-info "not implemented: new-server" {:opts opts})))

(defn invoke
  "Start the Tool Invocation call (SPEC.md §3). Assigns a call-id if absent and
  streams result frames to subscribers as it runs. Returns the call-id."
  [srv call]
  (throw (ex-info "not implemented: invoke" {:call call})))

(defn cancel
  "Cancel the invocation with call-id, emitting its terminal cancelled result."
  [srv call-id]
  (throw (ex-info "not implemented: cancel" {:call-id call-id})))

(defn close
  "Close the invocation (or thread) identified by id, emitting a terminal close."
  [srv id]
  (throw (ex-info "not implemented: close" {:id id})))

(defn subscribe
  "Register a subscriber for one invocation. cb is called with each result frame
  as it streams; the terminal result is the last call. Returns a subscription
  handle that close!-on-the-handle releases."
  [srv call-id cb]
  (throw (ex-info "not implemented: subscribe" {:call-id call-id})))

(defn on-result
  "Register cb to be called once with the terminal result of call-id."
  [srv call-id cb]
  (throw (ex-info "not implemented: on-result" {:call-id call-id})))

(defn close!
  "Stop the server and release subscriptions (does not close vm-pool/fanout)."
  [srv]
  (throw (ex-info "not implemented: close!" {})))
