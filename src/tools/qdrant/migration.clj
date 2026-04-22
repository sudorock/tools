(ns tools.qdrant.migration
  (:require
   [glass.service.qdrant :as qdrant]))

(def collection-name "tools")

(def vector-size 3072)

(def payload-indexes
  [{:field :action_name :schema-type :keyword}
   {:field :safety      :schema-type :keyword}
   {:field :content     :schema-type :text}])

(defn run
  [client]
  (when-not (qdrant/collection-exists? client {:collection-name collection-name})
    (qdrant/create-collection client {:collection-name collection-name
                                      :vector-size     vector-size
                                      :distance        :cosine}))
  (doseq [idx payload-indexes]
    (qdrant/create-payload-index client (assoc idx :collection-name collection-name))))
