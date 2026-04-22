(ns tools.main
  (:require
   [tools.system :as system]
   [tools.actions])
  (:gen-class))

(defn -main
  [& _args]
  (system/init))
