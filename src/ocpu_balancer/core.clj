(ns ocpu-balancer.core
  (:require
   [ocpu-balancer.handler :refer [app init destroy]]
   [ocpu-balancer.util :refer [canonical-host in-dev]]
   [ring.middleware.reload :as reload]
   [org.httpkit.server :as http-kit]
   [environ.core :refer [env]]
   [taoensso.timbre :as timbre])
  (:gen-class))

;contains function that can be used to stop http-kit server
(defonce server (atom nil))

(defn start-server [port]
  (init)
  (reset! server
          (http-kit/run-server
           (if in-dev (reload/wrap-reload #'app) app)
           {:thread 32
            :port port})))

(defn stop-server []
  (when @server
    (destroy)
    (@server :timeout 100)
    (reset! server nil)))

(defn -main [& args]
  (.addShutdownHook (Runtime/getRuntime) (Thread. stop-server))
  (start-server (Integer/parseInt (str (env :port))))
  (timbre/info canonical-host "server started"))
