(ns tools.actions.token
  (:require
   [glass.python.token :as python.token]))

(def ^:private default-encoding-description
  "Optional tiktoken encoding name. Defaults to cl100k_base.")

(defn- token-opts
  [{:keys [encoding]}]
  (cond-> {} encoding (assoc :encoding encoding)))

(defn ^{:tool/name         :token/count-text
        :tool/safety       :tool.safety/safe
        :tool/description  "Count tokens in the provided text using the shared Python tiktoken runtime."
        :tool/input-schema [:map {:closed true}
                            [:text {:description "Text to tokenize."} :string]
                            [:encoding {:optional true :description default-encoding-description}
                             [:maybe :string]]]}
  count-text
  [{:python/keys [runtime]} {:keys [text] :as params}]
  {:count (python.token/count-text runtime text (token-opts params))})

(defn ^{:tool/name         :token/count-file
        :tool/safety       :tool.safety/safe
        :tool/description  "Count tokens in the UTF-8 contents of a file using the shared Python tiktoken runtime."
        :tool/input-schema [:map {:closed true}
                            [:path {:description "Path to the UTF-8 text file to tokenize."} :string]
                            [:encoding {:optional true :description default-encoding-description}
                             [:maybe :string]]]}
  count-file
  [{:python/keys [runtime]} {:keys [path] :as params}]
  {:count (python.token/count-file runtime path (token-opts params))})
