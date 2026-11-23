(ns faraday.trace)
(def bound?*file* (bound? #'*file*))
(def *file*-val (when (bound? #'*file*) *file*))
