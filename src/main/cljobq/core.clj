(ns cljobq.core
  (:require
    [clj-uuid :as uuid]
    [clojure.core.async :as async]
    [clojure.spec.alpha :as s]
    [java-time :as jt]
    [migratus.core :as migratus]
    [cljobq.cron :as cron]
    [cljobq.job :as job]
    [cljobq.util :as util]
    [cljobq.worker :as worker]))

(defonce global-ctx*
  (atom nil))

(defn make-context
  "Return a new cljobq context based on the config hash passed in.
  cljobq does not use connection pooling internally, however, connection
  pooling can be enabled by using a db-spec with a data source set using
  hikari-cp or c3p0.

  All options except for `:db` can be overriden per queue in the queue-defs
  passed to `cljobq.core/start`.

  Options:

  - `:db`                   A `clojure.java.jdbc` db-spec map. Required.
  - `:default-timeout`      The default job timeout, in seconds. Defaults to
                            1800 seconds.
  - `:default-max-timeout`  The default maximum job timeout, including any
                            backoff. Defaults to 10 times the default timeout.
  - `:default-max-attempts` The default maximum number of attempts/retries to
                            try and run a job for before marking it as failed.
                            Defaults to 3.
  - `:default-num-threads`  The default number of worker threads for a queue.
                            Defaults to 1.
  - `:app/context`          An optional application-specific context passed
                            as first argument to all job function invocations.
                            If not provided, the parameter will not omitted
                            from the job function invocation.

  Example:

  ```clojure
  (def jobq-context
    (cljobq.core/make-context
      {:db {:connection-uri \"jdbc:postgresql://localhost:5432/jobqtest?user=test&password=test\"}
       :default-timeout 180}))
  ```"
  [{:keys [db
           default-timeout
           default-max-timeout
           default-max-attempts
           default-num-threads]
    :or {default-timeout 1800
         default-max-timeout (* default-timeout 10)
         default-num-threads 1
         default-max-attempts 3}
    :as context}]
  (cond->
    {:db db
     :run-info* (atom nil)
     :default-timeout default-timeout
     :default-num-threads default-num-threads
     :default-max-timeout default-max-timeout
     :default-backoff-factor 1.8
     :default-max-attempts default-max-attempts
     :default-poll-interval 30}

    ;; Copy over :app/context, if specified
    (contains? context :app/context)
    (assoc :app/context (:app/context context))))

(defn set-context!
  "Set the global context `cljobq.core/global-ctx*` based on the passed in
  config `v`. See `cljobq.core/make-context` for more information on the
  configuration options.

  The global context serves as an implicit context to all other functions
  in the absence of an explicit context.

  Example:

  ```clojure
  (cljobq.core/set-context!
    {:db {:connection-uri \"jdbc:postgresql://localhost:5432/jobqtest?user=test&password=test\"}
     :default-timeout 180})
  ```"
  [v]
  (reset! global-ctx* (make-context v)))

(defn ^:no-doc context->queue-defaults
  "Provide default queue settings based on the context values."
  [context]
  {:num-threads (:default-num-threads context)
   :max-attempts (:default-max-attempts context)
   :timeout (:default-timeout context)
   :max-timeout (:default-max-timeout context)
   :backoff-factor (:default-backoff-factor context)
   :poll-interval (:default-poll-interval context)})

(defn enqueue
  "Enqueue a job based on a job description with an optionally specfied
  explicit `context`.
  If a job with the given `job-name` for the specified `queue` already
  exists, the existing job will be updated instead.
  If no `job-name` is given, a uuid-based name will be assigned automatically.
  An `interval` string (unix cron format) can be specified to create a
  recurring job.
  Errors in the interval specification cause an exception to be thrown.
  Returns the newly inserted job.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:job-name`  A name for the job. A job name is unique within a queue. If
                 not specified, a UUID-based random name will be used instead.
  - `:queue`     A queue name to enqueue the job to. Required.
  - `:actor`     A var-quoted function to run as part of this job. Required.
  - `:args`      A vector of arguments to pass to the job function.
  - `:interval`  A string-based UNIX cron expression defining the interval
                 at which to run a job. If specified, a recurring job is
                 created. If omitted or nil, the job will only run once.
  - `:run-at`    The date/time at which to run the job. If omitted, the job
                 will be scheduled to run immediately if it is a one-off job,
                 or at the next recurrence if an `interval` was specified.

  Example:

  ```clojure
  (cljobq.core/enqueue
    {:actor #'*-and-log
     :args [9 6]
     :queue \"math\"})
  ```

  Example return value:

  ```clojure
  {:args [9 6]
   :job-name \"10189f80-81ab-11e8-9e30-698897b5b41d\"
   :last-error nil
   :queue \"math\"
   :status \"pending\"
   :id 18
   :picked-at nil
   :interval nil
   :created-at #inst \"2018-07-07T06:00:34.430781000-00:00\"
   :run-at #inst \"2018-07-07T06:00:34.424000000-00:00\"
   :attempt 0
   :related-job-id nil
   :actor \"user/*-and-log\"}
  ```"

  [{:keys [context queue job-name interval actor args]
    :or {context @global-ctx*
         job-name (uuid/v1)
         interval nil
         args []}}]
  {:pre [(contains? context :db)
         (string? queue)
         (var? actor)
         (or (nil? interval)
             (cron/validate interval))]}
  (let [actor-str (util/fnvar->str actor)
        payload-str (pr-str args)
        run-at (if interval
                 (cron/next-run interval)
                 (jt/offset-time))]
    (job/dbjob->friendly-job
      (job/upsert-job!
        (:db context)
        {:queue queue
         :job-name job-name
         :actor actor-str
         :payload payload-str
         :interval interval
         :run-at (util/->offset-time-zulu run-at)}))))

(defn delete-job
  "Delete a specific job `job-name` from the specified queue. Deleting a job
  by name only really makes sense for recurring jobs. Returns the number of
  jobs deleted.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:job-name`  The name of the job to delete. Required.
  - `:queue`     The name of the queue to delete the job from. Required.

  Example:

  ```clojure
  (cljobq.core/delete-job {:queue \"math\", :job-name \"algebra-on-wednesdays\"})
  ```"
  [{:keys [context queue job-name]
    :or {context @global-ctx*}}]
  {:pre [(contains? context :db)
         (string? queue)
         (string? job-name)]}
  (job/delete-job-by-name!
    (:db context)
    {:queue queue
     :job-name job-name}))

(defn delete-queue-jobs
  "Delete all jobs from the specified queue. Returns the number of jobs
  deleted.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:queue`     The name of the queue. Required.

  Example:

  ```clojure
  (cljobq.core/delete-queue-jobs {:queue \"math\"})
  ```"
  [{:keys [context queue]
    :or {context @global-ctx*}}]
  {:pre [(contains? context :db)
         (string? queue)]}
  (job/delete-queue-jobs!
    (:db context)
    {:queue queue}))

(defn list-jobs
  "List all non-failed jobs. If `queue` is specified, only jobs for that queue
  will be shown.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:queue`     The name of the queue. If omitted, jobs from all queues will
                 be shown.

  Example:

  ```clojure
  (cljobq.core/list-jobs)
  ```

  Example return value:

  ```clojure
  ({:args [9 6]
    :job-name \"10189f80-81ab-11e8-9e30-698897b5b41d\"
    :last-error nil
    :queue \"math\"
    :status \"pending\"
    :id 18
    :picked-at nil
    :interval nil
    :created-at #inst \"2018-07-07T06:00:34.430781000-00:00\"
    :run-at #inst \"2018-07-07T06:00:34.424000000-00:00\"
    :attempt 0
    :related-job-id nil
    :actor \"user/*-and-log\"}
   {:args [:a 81]
    :job-name \"regular-math-err\"
    :last-error nil
    :queue \"queue-c\"
    :status \"scheduled\"
    :id 22
    :picked-at nil
    :interval \"15 23 * * *\"
    :created-at #inst \"2018-07-07T06:56:35.294888000-00:00\"
    :run-at #inst \"2018-07-07T22:15:00.000000000-00:00\"
    :attempt 0
    :related-job-id nil
    :actor \"user/*-and-log\"})
  ```"
  ([] (list-jobs {}))
  ([{:keys [context queue]
     :or {context @global-ctx*
          queue nil}}]
   (job/list-jobs (:db context) queue)))

(defn list-failed-jobs
  "List all failed jobs. If `queue` is specified, only jobs for that queue
  will be shown.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:queue`     The name of the queue. If omitted, jobs from all queues will
                 be shown.

  Example:

  ```clojure
  (cljobq.core/list-failed-jobs {:queue \"queue-c\"})
  ```

  Example return value:

  ```clojure
  ({:args [14 :e]
    :job-name \"929238f0-7e2b-11e8-9580-698897b5b41d\"
    :last-error \"Error from actor `user/*-and-log` payload `[14 :e]`: clojure.lang.Keyword cannot be cast to java.lang.Number\\nclojure.lang.Numbers.multiply(Numbers.java:148)\\nuser$_STAR__and_log.invokeStatic(form-init6140559550272751977.clj:2)\\nuser$_STAR__and_log.invoke(form-init6140559550272751977.clj:1) ...\"
    :queue \"queue-c\"
    :status nil
    :id 12
    :picked-at #inst \"2018-07-04T20:43:12.000747000-00:00\"
    :interval nil
    :created-at #inst \"2018-07-02T19:10:24.264601000-00:00\"
    :run-at nil
    :attempt 5
    :related-job-id nil
    :actor \"user/*-and-log\"})
  ```"
  ([] (list-failed-jobs {}))
  ([{:keys [context queue]
     :or {context @global-ctx*
          queue nil}}]
   (job/list-failed-jobs (:db context) queue)))

(defn list-recurring-jobs
  "List all recurring jobs. If `queue` is specified, only jobs for that queue
  will be shown.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:queue`     The name of the queue. If omitted, jobs from all queues will
                 be shown.

  Example:

  ```clojure
  (cljobq.core/list-recurring-jobs {:queue \"math\"})
  ```

  Example return value:

  ```clojure
  ({:args [:a 81]
    :job-name \"regular-math-err\"
    :last-error nil
    :queue \"queue-c\"
    :status \"scheduled\"
    :id 22
    :picked-at nil
    :interval \"15 23 * * *\"
    :created-at #inst \"2018-07-07T06:56:35.294888000-00:00\"
    :run-at #inst \"2018-07-07T22:15:00.000000000-00:00\"
    :attempt 0
    :related-job-id nil
    :actor \"user/*-and-log\"})
  ```"
  ([] (list-recurring-jobs {}))
  ([{:keys [context queue]
     :or {context @global-ctx*
          queue nil}}]
   (job/list-recurring-jobs (:db context) queue)))

(defn db-migrate!
  "Run cljobq database migrations to ensure the database schema is set up and
  up-to-date.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.

  Example:

  ```clojure
  (cljobq.core/db-migrate!)
  ```"
  ([] (db-migrate! {}))
  ([{:keys [context]
     :or {context @global-ctx*}}]
   {:pre [(contains? context :db)]}
   (migratus/migrate {:store :database
                      :migration-dir "cljobq/migrations"
                      :migration-table-name "cljobq_migrations"
                      :db (:db context)})))

(defn start
  "Given a set of queue definitions in `queues`, start the worker threads for
  each of the defined queues and start processing jobs.
  Returns a `run-info` which can be used to stop the threads (see
  `cljobq.core/stop` for more information.

  Options:

  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead.
  - `:queues`    A map of queue-name -> queue-def. A queue def can an empty
                 map, in which case the defaults of the `context` will be used
                 for all options.

  Each queue-def is a map with the following options (defaults are taken from
  the context - see `cljobq.core/make-context`):

  - `:num-threads`     The number of worker threads to start for this queue.
  - `:max-attempts`    The maximum number of attempts/retries to try and run
                       a job on this queue for before marking it as failed.
  - `:timeout`         The job timeout, in seconds.
  - `:max-timeout`     The maximum job timeout, including any backoff.
  - `:backoff-factor`  The backoff factor to use when calculating when to retry
                       a job. Calculated as: `random() * timeout + min(max-timeout, timeout * backoff-factor ^ attempt-number)`. A `backoff-factor` of 1 defines a constant backoff, whilst a factor greater than 1 defines an exponential backoff.
  - `:poll-interval`   The number of seconds the queue's workers will sleep
                       for if no job is ready to run.

  Example:

  ```clojure
  (def jobq-runinfo
    (cljobq.core/start
      {:queues
       {:default
        {}
        :math
        {:num-threads 4
         :max-attempts 1
         :poll-interval 5}
        :email
        {:num-threads 2
         :timeout 600
         :max-timeout 14400
         :backoff-factor 1.9}}}))
  ```"
  [{:keys [context queues]
    :or {context @global-ctx*}}]
  {:pre [(contains? context :db)
         (pos? (count queues))]}
  (let [stop-ch
        (async/chan)
        thread-chs
        (-> (for [[qname qdef] queues]
              (worker/start-queue-threads
                context
                (name qname)
                (merge (context->queue-defaults context) qdef)
                stop-ch))
            flatten
            doall)
        run-info
        {:stop-ch stop-ch
         :thread-chs thread-chs}]
    (reset! (:run-info* context) run-info)))

(defn stop
  "Stop all worker threads. Can be passed either a `run-info` returned by
  `cljobq.core/start` or a context. If passed a context, only the threads
  started by the last call to `cljobq.core/start` with that context will
  be stopped.

  Example:

  ```clojure
  (cljobq.core/stop jobq-runinfo)
  ```"
  ([]
   (stop @global-ctx*))
  ([run-info-or-ctx]
   (let [run-info (if (contains? run-info-or-ctx :stop-ch)
                    run-info-or-ctx
                    @(:run-info* run-info-or-ctx))
         {:keys [stop-ch thread-chs]} run-info]
     (async/close! stop-ch)
     (async/<!! (async/merge thread-chs)))))

(s/def ::queue string?)
(s/def ::num-threads int?)
(s/def ::max-attempts int?)
(s/def ::timeout int?)
(s/def ::max-timeout int?)
(s/def ::backoff-factor number?)
(s/def ::poll-interval int?)

(s/def ::actor var?)
(s/def ::args vector?)
(s/def ::job-name string?)
(s/def ::run-at util/valid-date-time?)
(s/def ::interval (s/nilable cron/valid?))

(s/def ::context
  (s/keys
    :req-un [::db]
    :opt    [:app/context]
    :opt-un [::default-timeout
             ::default-num-threads
             ::default-max-timeout
             ::default-backoff-factor
             ::default-max-attempts
             ::default-poll-interval]))

(s/def ::context*
  (s/keys
    :req-un [::db
             ::default-timeout
             ::default-num-threads
             ::default-max-timeout
             ::default-backoff-factor
             ::default-max-attempts
             ::default-poll-interval
             ::run-info*]
    :opt    [:app/context]))

(s/def ::run-info
  (s/keys
    :req-un [::stop-ch
             ::thread-chs]))

(s/def ::qdef
  (s/keys
    :opt-un [::num-threads
             ::max-attempts
             ::timeout
             ::max-timeout
             ::backoff-factor
             ::poll-interval]))

(s/def ::queues (s/map-of simple-keyword? ::qdef
                          :min-count 1))

(s/def ::job
  (s/keys
    :req-un [::queue ::actor]
    :opt-un [::args ::job-name ::run-at ::interval]))

(s/def ::context-arg
  (s/keys
    :opt-un [::context]))

(s/fdef make-context
        :args (s/cat :context ::context))

(s/fdef set-context!
        :args (s/cat :context ::context))

(s/fdef enqueue
        :args (s/cat :job (s/merge ::context-arg ::job)))

(s/fdef delete-job
        :args (s/cat :args
                     (s/keys
                       :req-un [::queue ::job-name]
                       :opt-un [::context])))

(s/fdef delete-queue-jobs
        :args (s/cat :args
                     (s/keys
                       :req-un [::queue]
                       :opt-un [::context])))

(s/fdef db-migrate!
        :args (s/cat :args
                     (s/keys
                       :opt-un [::context])))

(s/def ::start-args
  (s/keys
    :opt-un [::context]
    :req-un [::queues]))

(s/fdef start
        :args (s/cat :args ::start-args))

(s/fdef stop
        :args (s/cat :args
                     (s/or :run-info ::run-info
                           :context  ::context*)))
