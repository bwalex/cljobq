(ns cljobq.web.middleware
  (:require
    [cljobq.core :refer [global-ctx*]]
    [hiccup.util :refer [with-base-url]]))

(defn wrap-context [handler config]
  (let [db (cond
             (contains? config :db)       (:db config)
             (contains? config :context)  (get-in config [:context :db])
             :else                        (:db @global-ctx*))
        base-url (get config :base-url nil)]
    (fn [request]
      (with-base-url base-url
        (handler (assoc request ::context {:db db}))))))
