(ns tools.service
  (:require
   [io.pedestal.http.route :as route]
   [io.pedestal.interceptor :as interceptor]
   [io.pedestal.service.interceptors :as interceptors]
   [tools.mcp.http :as mcp-http]))

(defn- ctx-interceptor
  [ctx]
  (interceptor/interceptor
   {:name  :ctx
    :enter (fn [req-ctx] (assoc-in req-ctx [:request :ctx] ctx))}))

(def routes
  #{["/mcp" :post [mcp-http/mcp-handler]]})

(defn http-interceptors
  [ctx]
  [interceptors/log-request
   interceptors/not-found
   route/query-params
   (ctx-interceptor ctx)
   (route/router #(route/expand-routes (deref #'routes)))])
