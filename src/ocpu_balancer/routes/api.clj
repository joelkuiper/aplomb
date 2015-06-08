(ns ocpu-balancer.routes.api
  (:require
   [ocpu-balancer.util :refer [dissoc-in]]
   [compojure.core :refer [context defroutes OPTIONS POST GET]]
   [ring.util.http-response :as http]
   [clj-http.client :as client]
   [taoensso.timbre :as timbre]
   [clojure.java.io :as io]
   [durable-queue :as q]
   [clojure.core.async :as async]
   ))

(declare process)

(def qk :ocpu) ; key for the queue

;; Durable queue, new posts will be enqeued and consumed by upstreams
(defonce q (q/queues "/tmp" {}))

(defonce upstreams
  [{:uri "http://192.168.174.128" :cores 1}])

(defonce tasks (atom {}))

(defn start-consumers
  "Start consumers threads that will consume work
  from the q and put the results into the out-chan."
  [upstreams]
  (timbre/info "initializing load balanced consumers")
  (doseq [upstream upstreams]
    (let [base (:uri upstream)]
      (timbre/info "starting" base)
      (dotimes [core (:cores upstream)]
        (timbre/info "awaiting ..." base "core" core)
        (async/go
          (while true
            (let [task (q/take! q qk)
                  id (deref task)
                  data (get @tasks id)
                  result (process base (:req data))]
              (swap! tasks assoc-in [id :status] ::completed)
              (q/complete! task))))))))

(defn init! [] (start-consumers upstreams))

(defn process
  [base req]
  (timbre/debug req)
  (let [f (get-in req [:query-params "f"])
        uri (str  base "/" f)
        upstream-req (dissoc-in req  [:headers "content-length"])]
    (timbre/info "sending off to" uri)
    (client/post uri upstream-req)))


(defn uuid [] (str (java.util.UUID/randomUUID)))

(defn enqueue
  [req]
  (let [id (uuid)
        chan (:async-channel req)
        bare-req (dissoc req :async-channel)
        stats (q/stats q)]
    (swap! tasks assoc id {:req bare-req :status ::queued})
    (q/put! q qk id)
    (timbre/info "accepted" id stats)
    (http/ok stats)))

(defroutes api-routes
  (context "/api" []
           (POST "/" [:as req] (enqueue req))))
