(ns tools.mcp.search
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [glass.service.openai.embedding :as openai.embedding]
   [glass.service.qdrant :as qdrant]
   [tools.qdrant.migration :as migration]))

(def ^:private embedding-model "text-embedding-3-large")
(def ^:private rrf-k 60)

(defn- tokenize
  [text]
  (->> (str/lower-case (or text ""))
       (re-seq #"[a-z0-9][a-z0-9_-]*")
       vec))

(defn- lexical-score
  [q-tokens content]
  (count (set/intersection (set q-tokens) (set (tokenize content)))))

(defn- rrf-scores
  [ranked]
  (into {}
        (map-indexed (fn [i p] [(str (:id p)) (/ 1.0 (+ rrf-k (inc i)))]))
        ranked))

(defn- dense
  [qdrant-client openai-client query limit]
  (let [v (openai.embedding/generate openai-client embedding-model query)]
    (qdrant/search-points qdrant-client {:collection-name migration/collection-name
                                         :vector          v
                                         :limit           (max 50 (* 5 limit))})))

(defn- lexical
  [qdrant-client q-tokens limit]
  (when (seq q-tokens)
    (->> (qdrant/scroll-points qdrant-client {:collection-name migration/collection-name
                                              :limit           (max 200 (* 20 limit))})
         (map (fn [p]
                (assoc p :lex-score (lexical-score q-tokens (get-in p [:payload "content"])))))
         (remove #(zero? (:lex-score %)))
         (sort-by :lex-score >))))

(defn- merge-rrf
  [dense-ranked lex-ranked limit]
  (let [d-rrf    (rrf-scores dense-ranked)
        l-rrf    (rrf-scores lex-ranked)
        ids      (set/union (set (keys d-rrf)) (set (keys l-rrf)))
        pl-by-id (into {}
                       (map (fn [p] [(str (:id p)) (:payload p)]))
                       (concat dense-ranked lex-ranked))]
    (->> ids
         (map (fn [id]
                {:payload (get pl-by-id id)
                 :score   (+ (get d-rrf id 0.0) (get l-rrf id 0.0))}))
         (sort-by :score >)
         (take limit)
         vec)))

(defn- hit
  [{:keys [payload score]}]
  {:action       (get payload "action_name")
   :description  (get payload "description")
   :safety       (get payload "safety")
   :input_schema (get payload "input_schema")
   :score        score})

(defn search
  [ctx {:keys [query limit] :or {limit 10}}]
  (let [qdrant-client (:qdrant/client ctx)
        openai-client (:openai/client ctx)
        limit         (long limit)
        tokens        (tokenize query)
        d-ranked      (dense   qdrant-client openai-client query  limit)
        l-ranked      (lexical qdrant-client tokens limit)]
    (mapv hit (merge-rrf d-ranked l-ranked limit))))
