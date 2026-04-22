(ns tools.system
  (:require
   [clojure.java.io :as io]
   [glass.python :as python]
   [glass.reader :as reader]
   [glass.service.openai.client :as openai.client]
   [glass.service.qdrant :as qdrant]
   [glass.system :as system]
   [nrepl.server :as nrepl]
   [tools.pedestal :as pedestal]
   [tools.qdrant.migration :as qdrant.migration]
   [tools.qdrant.sync :as qdrant.sync]))

(defonce ^:private -sys-vol (volatile! nil))

(defn -sys
  "For debugging purposes only."
  []
  (deref -sys-vol))

(defmethod system/init-key :python/runtime
  [_ {:keys [python-executable]}]
  (python/init python-executable))

(defmethod system/init-key :openai/client
  [_ {:keys [api-key]}]
  (openai.client/init api-key))

(defmethod system/halt-key! :openai/client
  [_ client]
  (openai.client/close client))

(defmethod system/init-key :qdrant/client
  [_ config]
  (let [client (qdrant/init config)]
    (qdrant.migration/run client)
    client))

(defmethod system/halt-key! :qdrant/client
  [_ client]
  (qdrant/close client))

(defmethod system/init-key :nrepl/server
  [_ {:keys [host port]}]
  (nrepl/start-server :port port :bind host))

(defmethod system/halt-key! :nrepl/server
  [_ server]
  (nrepl/stop-server server))

(defmethod system/init-key :pedestal/server
  [_ {:keys [ctx host port join?]}]
  (qdrant.sync/sync! ctx)
  (pedestal/start
   ctx
   {:host host
    :port port
    :join? join?}))

(defmethod system/halt-key! :pedestal/server
  [_ connector]
  (pedestal/stop connector))

(defn- read-cfg
  []
  (-> (io/resource "config.edn")
      reader/read-config))

(defn init
  []
  (vreset! -sys-vol (system/init (read-cfg))))
