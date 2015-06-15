(ns ocpu-balancer.security
  (:require
   [taoensso.timbre :as timbre]
   [environ.core :refer [env]]
   [buddy.core.nonce :as nonce]
   [buddy.core.codecs :as codecs]
   [buddy.auth :refer [authenticated? throw-unauthorized]]
   [buddy.auth.backends.token :refer [token-backend]]
   [buddy.sign.jws :as jws]))

(defn random-token [] (codecs/bytes->hex (nonce/random-bytes 32)))

(def secret (or (:api-secret env) (random-token)))

(defn sign
  [data]
  (jws/sign data secret))

(defn unsign
  [data]
  (jws/unsign data secret))

;; Authentication / Authorization

(defn should-be-authenticated
  [req]
  (authenticated? req))

(defn my-authfn
  [req token]
  (when (= token secret) "secure"))

;; Create an instance of auth backend.

(def auth-backend
  (token-backend {:authfn my-authfn}))
