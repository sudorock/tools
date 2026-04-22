(ns tools.mcp.http
  "HTTP transport for MCP server."
  (:require
   [glass.json :as json]
   [io.pedestal.interceptor :as interceptor]
   [tools.mcp.server :as mcp]))

(defn- request-message?
  [req]
  (and (string? (:method req))
       (some? (:id req))))

(defn- notification-message?
  [req]
  (and (string? (:method req))
       (nil? (:id req))))

(defn- response-message?
  [req]
  (and (nil? (:method req))
       (contains? req :id)
       (or (contains? req :result)
           (contains? req :error))))

(defn- message-type
  [req]
  (cond
    (request-message? req) :request
    (notification-message? req) :notification
    (response-message? req) :response))

(defn- error-response
  [status error]
  {:status  status
   :headers {"Content-Type" "application/json"}
   :body    (json/stringify (mcp/format-error-response error))})

(defn- invalid-request-response
  []
  (error-response 400
                  {:code    -32600
                   :message "Invalid Request"}))

(defn- handle-mcp-request
  [ctx body]
  (try
    (let [req          (json/parse body)
          message-type (message-type req)
          {:keys [id]} req]
      (cond
        (nil? message-type)
        (invalid-request-response)

        (= message-type :request)
        (let [result   (mcp/handle-request req ctx)
              response (mcp/format-response id result)]
          {:status  200
           :headers {"Content-Type" "application/json"}
           :body    (json/stringify response)})

        :else
        {:status 202}))
    (catch Exception _
      (error-response 400
                      {:code    -32700
                       :message "Parse error"}))))

(def mcp-handler
  (interceptor/interceptor
   {:name  :mcp-handler
    :enter (fn [{:keys [request] :as context}]
             (let [{:keys [body ctx]} request
                   body     (if (string? body) body (slurp body))
                   response (handle-mcp-request ctx body)]
               (assoc context :response response)))}))
