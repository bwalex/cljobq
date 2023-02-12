(ns cljobq.cron
  (:require [cljobq.util :as util]
            [java-time :as jt])
  (:import (com.cronutils.model Cron CronType)
           (com.cronutils.model.definition CronDefinition CronDefinitionBuilder)
           (com.cronutils.model.time ExecutionTime)
           (com.cronutils.parser CronParser)
           (java.time ZonedDateTime)))

(defonce ^:no-doc quartz-parser
  (let [d (CronDefinitionBuilder/instanceDefinitionFor CronType/QUARTZ)]
    (CronParser. d)))

(defonce ^:no-doc unix-parser
  (let [d (CronDefinitionBuilder/instanceDefinitionFor CronType/UNIX)]
    (CronParser. d)))

(defn ^:no-doc parse-quartz [v]
  (let [c (.parse quartz-parser v)]
    (.validate c)))

(defn ^:no-doc parse-unix [v]
  (let [c (.parse unix-parser v)]
    (.validate c)))

(defn validate
  "Validate the cron expression `v` based on the cron type `t`, which
  can be either `:unix` or `:quartz` and defaults to the former.
  Throws an IllegalArgumentException if validation fails."
  ([v]
   (validate :unix v))
  ([t v]
   {:pre [(contains? #{:quartz :unix} t)]}
   (if (= t :quartz)
     (parse-quartz v)
     (parse-unix v))))

(defn valid?
  "Validate the cron expression `v` based on the cron type `t`, which
  can be either `:unix` or `:quartz` and defaults to the former.
  Returns true or false."
  ([v]
   (valid? :unix v))
  ([t v]
   (try
     (do
       (validate t v)
       true)
     (catch IllegalArgumentException e
       false))))

(defn error-msg
  "Validate the cron expression `v` based on the cron type `t`, which
  can be either `:unix` or `:quartz` and defaults to the former.
  Returns `nil` if validation succeeds, and an error string otherwise."
  ([v]
   (valid? :unix v))
  ([t v]
   (try
     (do
       (validate t v)
       nil)
     (catch IllegalArgumentException e
       (.getMessage e)))))

(defn next-run-after
  "Get the next execution time for the cron expression `v` after
  date/time `dt`."
  ([v dt]
   (next-run-after :unix v dt))
  ([t v dt]
   (let [zdt (jt/with-zone-same-instant (jt/zoned-date-time dt) "UTC")
         cron (validate t v)
         et (ExecutionTime/forCron cron)]
     (util/->offset-time-zulu (.get (.nextExecution et zdt))))))

(defn next-run
  "Get the next execution time for the cron expression `v` from
  now."
  ([v]
   (next-run :unix v))
  ([t v]
   (next-run-after t v (jt/zoned-date-time))))
