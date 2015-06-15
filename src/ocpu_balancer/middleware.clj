(ns ocpu-balancer.middleware
  (:require
   [ocpu-balancer.util :refer [in-dev]]
   [ocpu-balancer.security :as security]
   [taoensso.timbre :as timbre]
   [environ.core :refer [env]]
   [clojure.java.io :as io]
   [selmer.middleware :refer [wrap-error-page]]
   [prone.middleware :refer [wrap-exceptions]]
   [ring.util.response :refer [redirect]]
   [buddy.auth.accessrules :refer [wrap-access-rules]]
   [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
   [ring.middleware.cors :refer [wrap-cors]]
   [ring.middleware.defaults :refer [api-defaults wrap-defaults]]
   [ring.middleware.format-params :refer [wrap-restful-params]]))

(defn wrap-internal-error [handler]
  (fn [req]
    (try
      (handler req)
      (catch Throwable t
        (timbre/error t)
        {:status 500
         :headers {"Content-Type" "text/html"}
         :body (-> "templates/error.html" io/resource slurp)}))))

(defn wrap-dev [handler]
  (if in-dev
    (-> handler
       wrap-error-page
       wrap-exceptions)
    handler))

(defn wrap-formats [handler]
  (wrap-restful-params handler :formats [:json-kw]))

(defn wrap-base [handler]
  (-> handler
     (wrap-authorization security/auth-backend)
     (wrap-authentication security/auth-backend)

     (wrap-cors :access-control-allow-origin [#".*"]
                :access-control-allow-methods [:options :get :put :post :put])
     wrap-dev
     wrap-formats
     (wrap-defaults api-defaults)
     wrap-internal-error))
