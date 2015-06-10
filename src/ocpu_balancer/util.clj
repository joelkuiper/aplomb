(ns ocpu-balancer.util
  (:require [environ.core :refer [env]]))

(def canonical-host
  (or (:host-addr env)
     (str (.getCanonicalHostName (java.net.InetAddress/getLocalHost)) ":" (env :port))))

(def truthy?  #{"true" "TRUE" "True" "yes" "YES" "y" "1"})
(def in-dev (truthy? (str (:dev env))))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))
