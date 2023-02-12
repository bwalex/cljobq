(ns cljobq.util
  (:require
    [java-time :as jt]))

(defn fnvar->str [f]
  (let [fn-meta (meta f)
        fn-ns (ns-name (:ns fn-meta))]
    (str fn-ns "/" (:name fn-meta))))

(defn str->fnvar [v]
  (find-var (symbol v)))

(defn ->offset-time-zulu [dt]
  (when dt
    (jt/offset-date-time dt "+0")))

(defn valid-date-time? [dt]
  (try
    (jt/offset-date-time dt)
    true
    (catch Exception _
      false)))
