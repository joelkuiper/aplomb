(ns ocpu-balancer.routes.home
  (:require [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [clojure.java.io :as io]))

(defn home-page []
  "hey, hello")


(defroutes home-routes
  (GET "/" [] (home-page)))
