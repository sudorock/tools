(ns tools.main
  (:require
   [glass.log :as log]
   [tools.system :as system]
   [tools.actions])
  (:gen-class))

(defn -main
  [& _args]
  (system/init)
  (log/info {:message "server started"}))
