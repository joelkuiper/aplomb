(ns ocpu-balancer.routes.api
  (:require
   [ocpu-balancer.util :refer [dissoc-in]]
   [compojure.core :refer [context defroutes OPTIONS POST GET]]
   [ring.util.http-response :as http]
   [ring.util.request :refer [request-url]]
   [clj-http.client :as client]
   [taoensso.timbre :as timbre]
   [clojure.java.io :as io]
   [clojure.core.async :as async]
   [clojurewerkz.urly.core :as urly]
   [durable-queue :as q]))

(declare process)

(def qk :queue) ; key for the queue

;; Durable queue, new posts will be enqeued and consumed by upstreams
(defonce q (q/queues "/tmp" {}))

(defonce upstreams
  [{:uri "http://192.168.174.128" :cores 1}])

(defonce tasks (atom {}))

(defn start-consumers
  "Start consumers threads that will consume work from the q"
  [upstreams]
  (timbre/info "initializing load balanced consumers")
  (doseq [upstream upstreams]
    (let [base (:uri upstream)]
      (timbre/info "starting" base)
      (dotimes [core (:cores upstream)]
        (timbre/info "awaiting ..." base "core" core)
        (async/go
          (while true
            (process base (q/take! q qk))))))))

(defn init! [] (start-consumers upstreams))

(defn send-upstream
  [base req]
  (let [f (get-in req [:query-params "f"])
        uri (str  base "/" f)
        upstream-req (dissoc-in req  [:headers "content-length"])]
    (timbre/info "sending off to" uri)
    (client/post uri upstream-req)))

(defn process
  [base task]
  (let [out-chan (async/chan)
        id (deref task)]
    (when-let [req (get-in @tasks [id :req])]
      (swap! tasks update-in [id] assoc :out-chan out-chan :base base)
      (async/put! out-chan (send-upstream base (get-in @tasks [id :req])))
      (async/take! out-chan #(swap! tasks assoc-in [id :results] %))
      (q/complete! task))))

(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn task-status-resp
  [req id]
  (let [req-url (urly/url-like (request-url req))
        result-url (.mutateQuery (.mutatePath req-url (str "/api/results/" id)) nil)]
    {:id id
     :requestUri (str req-url)
     :resultUri (str result-url)
     :queue (q/stats q)}))

(defn enqueue
  [req]
  (let [id (uuid)
        chan (:async-channel req)
        bare-req (dissoc req :async-channel)]
    (swap! tasks assoc id {:req bare-req})
    (q/put! q qk id)
    (http/ok (task-status-resp req id))))


(defn get-task
  [req]
  (let [id (get-in req [:params :id])]
    (get @tasks id)))

(defn results
  [req]
  (if-let [task (get-task req)]
    (:results task)
    (http/not-found)))

(defn proxy-results
  [req]
  (if-let [task (get-task req)]
    (let [loc (get-in req [:route-params :*])
          uri (str (:base task) "/" loc)]
      (timbre/debug uri)
      (client/get uri {:as :stream
                       :throw-exceptions false
                       :force-redirects true}))
    (http/not-found)))

(defroutes api-routes
  (context "/api" []
           (POST "/submit" [] enqueue)
           (GET "/results/:id" [] results)
           (GET "/results/:id/*" [] proxy-results)))
