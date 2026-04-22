(ns tools.mcp.tools
  (:require
   [malli.core :as m]
   [malli.error :as me]
   [malli.transform :as mt]
   [tools.utils :as utils]))

(def ^:private generic-input-schema
  {:type                 "object"
   :properties           {:action {:type "string"}
                          :params {:type "object"}}
   :required             ["action" "params"]
   :additionalProperties false})

(defn list-tools
  []
  [{:name        "safe"
    :description "Dispatch a safe action. `action` is a namespaced identifier (e.g. \"echo/echo\"); `params` is the action's input object."
    :inputSchema generic-input-schema
    :annotations {:readOnlyHint    true
                  :destructiveHint false}}
   {:name        "unsafe"
    :description "Dispatch an unsafe (state-changing) action."
    :inputSchema generic-input-schema
    :annotations {:readOnlyHint    false
                  :destructiveHint true}}])

(defn- resolve-handler
  [action]
  (let [target (keyword action)]
    (->> (utils/find-vars-by-meta #(contains? % :tool/name))
         (filter #(= target (:tool/name (meta %))))
         first)))

(defn- validate
  [schema params]
  (let [decoded (m/decode schema params mt/json-transformer)]
    (if-let [errs (me/humanize ((m/explainer schema) decoded))]
      {:error {:message "invalid params" :data errs}}
      {:params decoded})))

(defn- dispatch
  [required-safety ctx {:keys [action params]}]
  (if-let [v (resolve-handler action)]
    (let [{:tool/keys [safety input-schema]} (meta v)]
      (if (not= required-safety safety)
        {:error {:message  "safety mismatch"
                 :required required-safety
                 :actual   safety}}
        (let [{decoded :params verr :error} (validate input-schema params)]
          (if verr
            {:error verr}
            (try
              {:result ((var-get v) ctx decoded)}
              (catch Throwable t
                {:error {:message (or (.getMessage t) "internal error")}}))))))
    {:error {:message (str "unknown action: " action)}}))

(defn call
  [ctx tool-name args]
  (case tool-name
    "safe"   (dispatch :tool.safety/safe   ctx args)
    "unsafe" (dispatch :tool.safety/unsafe ctx args)
    {:error {:message (str "unknown tool: " tool-name)}}))
