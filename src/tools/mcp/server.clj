(ns tools.mcp.server
  (:require
   [glass.json :as json]
   [tools.mcp.tools :as handler]))

(def supported-protocol-version "2025-11-25")

(def supported-protocol-versions
  #{supported-protocol-version})

(defn- negotiate-protocol-version
  [req]
  (let [requested (get-in req [:params :protocolVersion])]
    (if (contains? supported-protocol-versions requested)
      requested
      supported-protocol-version)))

(defn handle-initialize
  [req]
  {:result
   {:protocolVersion (negotiate-protocol-version req)
    :capabilities    {:tools {:listChanged false}}
    :serverInfo      {:name    "tools"
                      :version "0.1.0"}}})

(defn handle-ping
  [_req]
  {:result {}})

(defn handle-tools-list
  [_req]
  {:result {:tools (handler/list-tools)}})

(defn- tool-content
  [value]
  [{:type "text"
    :text (json/stringify value)}])

(defn handle-tools-call
  [req ctx]
  (let [tool-name           (get-in req [:params :name])
        args                (get-in req [:params :arguments])
        {:keys [result error]} (handler/call ctx tool-name args)
        ret                 (or error result)]
    {:result
     {:content           (tool-content ret)
      :structuredContent {:data ret}
      :isError           (boolean error)}}))

(defn handle-request
  [req ctx]
  (case (:method req)
    "initialize" (handle-initialize req)
    "ping"       (handle-ping req)
    "tools/list" (handle-tools-list req)
    "tools/call" (handle-tools-call req ctx)
    {:error {:code    -32601
             :message (str "Method not found: " (:method req))}}))

(defn format-response
  [id {:keys [result error]}]
  (if error
    {:jsonrpc "2.0" :id id :error error}
    {:jsonrpc "2.0" :id id :result result}))

(defn format-error-response
  [error]
  {:jsonrpc "2.0" :id nil :error error})
