(ns tools.actions.system
  (:require
   [clojure.tools.namespace.repl :as repl]
   [glass.system :as glass-system]
   [tools.system :as system]))

;; Scope refresh to this project's source — otherwise refresh-all
;; tries to reload glass and other deps' source trees.
(repl/set-refresh-dirs "src")

(defn- refresh-result
  [result]
  (cond
    (= :ok result)             {:status "refreshed"}
    (instance? Throwable result) (throw result)
    :else                        (throw (ex-info (str "refresh failed: " result) {}))))

(defn ^{:tool/name         :tools/refresh
        :tool/safety       :tool.safety/unsafe
        :tool/input-schema :map
        :tool/description  "Reload all namespaces via clojure.tools.namespace.repl/refresh-all."}
  refresh
  [_ctx _params]
  (refresh-result (binding [*ns* *ns*] (repl/refresh-all))))

(defn ^{:tool/name         :tools/restart
        :tool/safety       :tool.safety/unsafe
        :tool/input-schema :map
        :tool/description  "Halt the system, reload all namespaces, and re-init. Runs asynchronously so the MCP response is flushed first."}
  restart
  [_ctx _params]
  (future
    (Thread/sleep 100)
    (some-> (system/-sys) glass-system/halt!)
    (binding [*ns* *ns*]
      (repl/refresh-all :after 'tools.system/init)))
  {:status "restarting"})
