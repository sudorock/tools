(ns tools.sync
  (:require
   [clojure.data :as data]
   [clojure.set :as set]
   [glass.service.openai.embedding :as openai.embedding]
   [glass.service.qdrant :as qdrant]
   [tools.qdrant.migration :as migration]
   [tools.utils :as utils])
  (:import
   [java.util UUID]))

(def ^:private embedding-model "text-embedding-3-large")

(defn- action-name-str
  [kw]
  (subs (str kw) 1))

(defn- point-uuid
  [action-name]
  (str (UUID/nameUUIDFromBytes (.getBytes ^String action-name "UTF-8"))))

(defn- safety-str
  [kw]
  (case kw
    :tool.safety/safe   "safe"
    :tool.safety/unsafe "unsafe"))

(defn- expected-point
  [v]
  (let [m       (meta v)
        action  (action-name-str (:tool/name m))
        desc    (or (:tool/description m) "")
        content (str action "\n\n" desc)]
    {:id      (point-uuid action)
     :payload {"action_name"  action
               "description"  desc
               "safety"       (safety-str (:tool/safety m))
               "input_schema" (pr-str (:tool/input-schema m))
               "content"      content}}))

(defn- expected-points
  []
  (mapv expected-point (utils/find-vars-by-meta #(contains? % :tool/name))))

(defn- existing-points
  [client]
  (->> (qdrant/scroll-points client {:collection-name migration/collection-name
                                     :limit           10000})
       (mapv (fn [p] {:id (:id p) :payload (:payload p)}))))

(defn- content-changed?
  [exp-by-id ext-by-id id]
  (not= (get-in exp-by-id [id :payload "content"])
        (get-in ext-by-id [id :payload "content"])))

(defn- classify
  [expected existing]
  (let [exp-by-id   (into {} (map (juxt :id identity)) expected)
        ext-by-id   (into {} (map (juxt :id identity)) existing)
        [ext-only exp-only _both] (data/diff ext-by-id exp-by-id)
        ext-ks      (set (keys (or ext-only {})))
        exp-ks      (set (keys (or exp-only {})))
        to-create   (set/difference exp-ks ext-ks)
        to-delete   (set/difference ext-ks exp-ks)
        changed     (set/intersection ext-ks exp-ks)
        content-fn? (partial content-changed? exp-by-id ext-by-id)
        re-embed    (filter content-fn? changed)
        re-payload  (remove content-fn? changed)]
    {:to-upsert      (mapv exp-by-id (concat to-create re-embed))
     :to-set-payload (mapv exp-by-id re-payload)
     :to-delete      (vec to-delete)}))

(defn- apply-plan!
  [qdrant-client openai-client {:keys [to-upsert to-set-payload to-delete]}]
  (when (seq to-upsert)
    (qdrant/upsert-points
     qdrant-client
     {:collection-name migration/collection-name
      :points          (mapv (fn [{:keys [id payload]}]
                               {:id      id
                                :vector  (openai.embedding/generate openai-client embedding-model (get payload "content"))
                                :payload payload})
                             to-upsert)}))
  (doseq [{:keys [id payload]} to-set-payload]
    (qdrant/set-payload qdrant-client {:collection-name migration/collection-name
                                       :payload         payload
                                       :point-ids       [id]}))
  (when (seq to-delete)
    (qdrant/delete-points qdrant-client {:collection-name migration/collection-name
                                         :point-ids       to-delete})))

(defn sync!
  [ctx]
  (let [qdrant-client (:qdrant/client ctx)
        openai-client (:openai/client ctx)
        expected      (expected-points)
        existing      (existing-points qdrant-client)
        plan          (classify expected existing)]
    (apply-plan! qdrant-client openai-client plan)
    {:upserted    (count (:to-upsert plan))
     :repayloaded (count (:to-set-payload plan))
     :deleted     (count (:to-delete plan))
     :total       (count expected)}))
