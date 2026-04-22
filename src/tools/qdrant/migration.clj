(ns tools.qdrant.migration
  (:require
   [glass.service.qdrant :as qdrant]))

(def collection-name "tools")

(def vector-size 3072)

(defn run
  [client]
  (when-not (qdrant/collection-exists? client {:collection-name collection-name})
    (qdrant/create-collection client {:collection-name collection-name
                                      :vector-size     vector-size
                                      :distance        :cosine})))
