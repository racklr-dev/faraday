;; faraday-memo: minimal bit primitive for the vendored SHA-256.
;; Vendored from racklr-dev/jolt-merkle (jolt.bit, rotate-right32) @ 06e135f.
(ns faraday.memo.bit
  "Minimal bit primitive for the vendored SHA-256.  jolt bit ops operate on
  64-bit Longs; rotate-right32 is the only operation the compression needs.")

(defn rotate-right32
  "Rotate a 32-bit word right by k bits (0 <= k < 32)."
  [x k]
  (let [x (bit-and x 0xFFFFFFFF)
        k (mod k 32)]
    (bit-and 0xFFFFFFFF
             (bit-or (unsigned-bit-shift-right x k)
                     (bit-shift-left x (- 32 k))))))
