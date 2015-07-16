(ns aplomb.handler
  (:require [compojure.core :refer [defroutes routes wrap-routes]]
            [aplomb.routes.home :refer [home-routes]]
            [aplomb.routes.api :refer [api-routes] :as api]
            [aplomb.util :refer [in-dev?]]
            [aplomb.middleware :as middleware]
            [compojure.route :as route]
            [taoensso.timbre :as timbre]
            [taoensso.timbre.appenders.rotor :as rotor]
            [environ.core :refer [env]]
            [clojure.tools.nrepl.server :as nrepl]))

(defonce nrepl-server (atom nil))

(defroutes base-routes
           (route/resources "/")
           (route/not-found "Not Found"))

(defn start-nrepl
  "Start a network repl for debugging when the :repl-port is set in the environment."
  []
  (when-let [port (and (env :repl-port) (Integer/parseInt (str (env :repl-port))))]
    (try
      (reset! nrepl-server (nrepl/start-server :port port))
      (timbre/info "nREPL server started on port" port)
      (catch Throwable t
        (timbre/error "failed to start nREPL" t)))))

(defn stop-nrepl []
  (when-let [server @nrepl-server]
    (nrepl/stop-server server)))

(defn init
  "init will be called once when
  app is deployed as a servlet on
  an app server such as Tomcat
  put any initialization code here"
  []
  (timbre/set-config!
   [:appenders :rotor]
   {:min-level             (if in-dev? :debug :info)
    :enabled?              true
    :async?                false ; should be always false for rotor
    :max-message-per-msecs nil
    :fn                    rotor/appender-fn})

  (timbre/set-config!
   [:shared-appender-config :rotor]
   {:path "ocpu_balancer.log" :max-size (* 512 1024) :backlog 10})

  (start-nrepl)
  (api/init!)
  (timbre/info "\n-=[ aplomb started successfully"
               (when in-dev? "using the development profile") "]=-"))

(defn destroy
  "destroy will be called when your application
   shuts down, put any clean up code here"
  []
  (timbre/info "aplomb is shutting down...")
  (stop-nrepl)
  (api/shutdown!)
  (timbre/info "shutdown complete!"))

(def app
  (-> (routes
      api-routes
      home-routes
      base-routes)
     middleware/wrap-base))
