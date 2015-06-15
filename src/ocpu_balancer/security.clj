(ns ocpu-balancer.security
  (:require
   [environ.core :refer [env]]
   [buddy.sign.jws :as jws]))

(def secret (:api-secret env))

(defn sign
  [data]
  (jws/sign data secret))

(defn unsign
  [data]
  (jws/unsign data secret))
