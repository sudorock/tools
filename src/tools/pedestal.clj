(ns tools.pedestal
  (:require
   [io.pedestal.http.jetty :as jetty]
   [io.pedestal.service.protocols :as pedestal]
   [tools.service :as service]))

(defn start
  [ctx {:keys [host port join?]}]
  (pedestal/start-connector!
   (jetty/create-connector
    {:host host
     :port port
     :join? join?
     :initial-context {}
     :interceptors (service/http-interceptors ctx)}
    nil)))

(defn stop
  [connector]
  (pedestal/stop-connector! connector))
