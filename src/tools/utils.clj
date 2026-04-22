(ns tools.utils)

(defn find-vars-by-meta
  "Find all vars in loaded namespaces whose metadata satisfies pred."
  [pred]
  (->> (all-ns)
       (mapcat ns-map)
       (keep (fn [[_ v]]
               (when (pred (meta v)) v)))))
