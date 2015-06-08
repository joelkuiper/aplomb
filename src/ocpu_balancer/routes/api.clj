(ns ocpu-balancer.routes.api
  (:require
   [ocpu-balancer.util :refer [dissoc-in]]
   [environ.core :refer [env]]
   [clojure.string :as str]
   [compojure.core :refer [context defroutes OPTIONS POST GET]]
   [ring.util.http-response :as http]
   [org.httpkit.server :as server]
   [ring.util.request :refer [request-url]]
   [clj-http.client :as client]
   [taoensso.timbre :as timbre]
   [clojure.java.io :as io]
   [crypto.random :refer [url-part]]
   [clojure.core.async :as async :refer [<! >! go chan]]
   [clojurewerkz.urly.core :as urly]
   [durable-queue :as q]))

(declare process)

(def qk :ocpu) ; key for the queue

;; Durable queue, new posts will be enqeued and consumed by upstreams
;; It's not actually durable, since we rely on an in-memory atom for the requests...
(defonce q (q/queues "/tmp" {}))

(defn- parse-list
  [s]
  (let [ks [:uri :cores]]
    (map #(apply array-map (interleave ks (str/split % #"\|"))) (str/split s #","))))

(defonce upstreams (parse-list (env :upstreams)))

(defonce tasks (atom {}))

(defn start-consumers
  "Start consumers threads that will consume work from the q"
  [upstreams]
  (timbre/info "initializing load balanced consumers")
  (doseq [upstream upstreams]
    (let [base (:uri upstream)]
      (timbre/info "starting" base)
      (dotimes [core (Integer/parseInt (:cores upstream))]
        (timbre/info "awaiting ..." base "core" core)
        (go
          (while true
            (process base (q/take! q qk))))))))

(defn init! [] (start-consumers upstreams))

(defn send-upstream
  [base req]
  (let [f (get-in req [:query-params "f"])
        uri (str  base "/" f)
        upstream-req (-> req
                        (dissoc-in [:headers "content-length"])
                        (assoc :throw-exceptions false))]
    (timbre/debug "sending off to" uri)
    (client/post uri upstream-req)))

(defn process
  [base task]
  (let [id (deref task)]
    (when-let [t (get @tasks id)]
      (timbre/debug "starting with" id)
      (swap! tasks update-in [id] assoc
             :base base
             :resp (future (send-upstream base (:req t))))
      (deref (get-in @tasks [id :resp])) ;; block future
      (timbre/debug "done with" id)
      (q/complete! task))))

(defn id [] (crypto.random/url-part 8))

(defn task-status-resp
  [req id]
  (let [request-uri (urly/url-like (request-url req))
        response-uri (.mutateQuery (.mutatePath request-uri (str "/api/response/" id)) nil)]
    {:id id
     :requestUri (str request-uri)
     :responseUri (str response-uri)
     :queue (q/stats q)}))

(defn enqueue
  [req]
  (let [id (id)
        bare-req (dissoc req :async-channel)]
    (swap! tasks assoc id {:req bare-req})
    (q/put! q qk id)
    (http/accepted (task-status-resp req id))))


(defn get-task
  [req]
  (let [id (get-in req [:params :id])]
    (get @tasks id)))

(defn response
  [req]
  (if-let [resp (:resp (get-task req))]
    (server/with-channel req channel
      (server/send! channel @resp)
      (server/on-close channel (fn [_] (timbre/debug "closing chan..."))))
    (http/not-found)))

(defn proxy-response
  [req]
  (if-let [task (get-task req)]
    (let [loc (get-in req [:route-params :*])
          uri (str (:base task) "/" loc)]
      (client/get uri {:as :stream
                       :throw-exceptions false
                       :force-redirects true}))
    (http/not-found)))

(defroutes api-routes
  (context "/api" []
           (POST "/submit" [] enqueue)
           (GET "/response/:id" [] response)
           (GET "/response/:id/*" [] proxy-response)))
