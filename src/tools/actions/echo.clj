(ns tools.actions.echo)

(defn ^{:tool/name         :echo/echo
        :tool/safety       :tool.safety/safe
        :tool/input-schema :map
        :tool/description  "Echo params back"}
  echo
  [_ctx params]
  params)
