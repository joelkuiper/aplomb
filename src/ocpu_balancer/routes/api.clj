(ns ocpu-balancer.routes.api
  (:require
   [buddy.auth.accessrules :refer [restrict]]
   [ocpu-balancer.util :refer [in-dev dissoc-in canonical-host]]
   [ocpu-balancer.cache :as cache]
   [ocpu-balancer.security :as security]
   [environ.core :refer [env]]
   [clojure.string :as str]
   [compojure.core :refer [context defroutes OPTIONS POST PUT GET]]
   [ring.util.http-response :as http]
   [org.httpkit.server :as server]
   [ring.util.request :refer [request-url]]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [taoensso.timbre :as timbre]
   [crypto.random :refer [url-part]]
   [clojure.core.async :as async :refer [<! >! go chan]]
   [clojurewerkz.urly.core :as urly]
   [durable-queue :as q]))

;;;;;;;;;;;
;; Set-up
;;;;;;;;;;;

(declare process)

(def qk :ocpu) ; key for the queue

;; Durable queue, new posts will be enqeued and consumed by upstreams
;; It's not actually durable, since we rely on an in-memory atom for the requests, but it has a nice API
(defonce q (q/queues "/tmp" {}))

(defn- parse-list
  [s]
  (let [ks [:uri :cores]]
    (map #(apply array-map (interleave ks (str/split % #"\|"))) (str/split s #","))))

(defonce upstreams (parse-list (env :upstreams)))

 ;; WARNING: THIS IS MUTABLE!
(defonce tasks (cache/create-cache :soft-values true))

(def base-uri
  (let [scheme (if in-dev "http" "https")
        base (str scheme "://" canonical-host)]
    (urly/url-like base)))

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
            (try
              (process base (q/take! q qk))
              (catch Exception e
                (do (timbre/error e)
                    (http/service-unavailable (.getMessage e)))))))))))

(defn init! [] (start-consumers upstreams))

;;;;;;;;;;;;;;
;; Processing
;;;;;;;;;;;;;;

(defn status-uri
  [req id]
  (str (.mutateQuery
        (.mutatePath base-uri (str "/api/status"))
        (str "id=" (security/sign {:id id})))))

(defn send-upstream
  [id base req]
  (let [f (get-in req [:query-params "url"])
        uri (str base "/" f)
        status-uri (status-uri req id)
        upstream-req
        (-> req
           (dissoc-in [:headers "content-length"])
           (assoc-in [:form-params "statusUri"] (json/encode status-uri))
           (assoc :throw-exceptions false))]
    (timbre/debug "sending off to" uri)
    (try
      (client/post uri upstream-req)
      (catch Exception e
        (do (timbre/error e) (http/service-unavailable (.getMessage e)))))))

(defn process
  [base task]
  (let [id (deref task)]
    (if-let [t (get tasks id)]
      (do
        (timbre/debug "starting with" id)

        (update-in tasks [id] assoc
                   :base base
                   :resp (deliver (:resp t) (send-upstream id base (:req t))))

        (deref (:resp (get tasks id))) ; block future
        (q/complete! task)
        (timbre/debug "done with" id))
      (do
        (q/complete! task)
        (timbre/warn "failed to process" id)))))

(defn random-id [] (crypto.random/url-part 8))

(defn task-status-resp
  [req id]
  (let [response-uri (.mutatePath base-uri (str "/api/response/" id))
        ws-protocol (if in-dev "ws" "wss")
        status-uri (.mutateProtocol
                    (.mutatePath base-uri (str "/api/status/" id "/ws")) ws-protocol)]
    {:id id
     :requestUri (request-url req)
     :responseUri (str response-uri)
     :statusUri (str status-uri)
     :queue (get (q/stats q) (name qk))}))

(defn enqueue
  [req]
  (let [id (random-id)
        bare-req (dissoc req :async-channel)]
    (assoc tasks id {:req bare-req :resp (promise)})
    (q/put! q qk id)
    (http/content-type
     (http/accepted
      (json/encode (task-status-resp req id)))
     "application/json")))

(defn- get-task [id] (get tasks id))

(defn- get-id [req] (get-in req [:params :id]))

(defn response
  [req]
  (let [task (get-task (get-id req))
        resp (:resp task)]
    (if resp
      (server/with-channel req channel
        (go
          (server/send! channel @resp true)))
      (http/not-found))))

(defn proxy-response
  [req]
  (if-let [task (get-task (get-id req))]
    (let [loc (get-in req [:route-params :*])
          uri (str (:base task) "/" loc)]
      (client/get uri {:as :stream
                       :throw-exceptions false
                       :force-redirects true}))
    (http/not-found)))

;;;;;;;;;;;
;; Updates
;;;;;;;;;;;

(def clients (ref {})) ; id -> set(chan)

(defn- alter-client
  [f msg id channel]
  (dosync
   (let [curr (get @clients id #{})]
     (timbre/debug msg "client for" id "|" (count curr))
     (alter clients assoc id (f curr channel)))))

(defn- connect-client [id channel] (alter-client conj "connect" id channel))
(defn- disconnect-client [id channel] (alter-client disj "disconnect" id channel))

(defn update-status
  [req]
  (let [id (:id (security/unsign (get-in req [:query-params "id"])))
        task (get-task id)
        update (if (:body req) (slurp (:body req)) "{}")]
    (if-not task
      (http/not-found)
      (dosync
       (assoc-in tasks [id :last-update] update) ;; update the last status
       (let [connected (get @clients id #{})]
         (doseq [client connected]
           (server/send! client update)))
       (http/no-content)))))

(defn status-updates
  [req]
  (let [id (get-id req)
        task (get-task id)
        last-update (get task :last-update "")]
    (if task
      (do
        (server/with-channel req channel
          (connect-client id channel)
          (server/send! channel last-update)
          (server/on-receive channel (fn [e] (timbre/warn "unexpected" e "for" id)))
          (server/on-close channel (fn [_] (disconnect-client id channel)))))
      (http/not-found))))


;;;;;;;;;;;
;; Routes
;;;;;;;;;;;

(defroutes api-routes
  (context "/api" []
           (POST "/submit" [] (restrict enqueue {:handler security/should-be-authenticated}))
           (GET "/response/:id" [] response)
           (GET "/response/:id/*" [] proxy-response)

           (GET "/status/:id/ws" [] status-updates)
           (PUT "/status" [] update-status)))
