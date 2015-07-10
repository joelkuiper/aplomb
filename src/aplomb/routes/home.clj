(ns aplomb.routes.home
  (:require [compojure.core :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [clojure.java.io :as io]))

(defn home-page []
  "we're alive")


(defroutes home-routes
  (GET "/" [] (home-page)))
