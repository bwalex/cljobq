(ns cljobq.worker
  (:require
    [clojure.core.async :as async]
    [clojure.edn :as edn]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [java-time :as jt]
    [cljobq.cron :as cron]
    [cljobq.job :as job]
    [cljobq.util :as util]))


(defn ^:no-doc -log-worker-error [db job e]
  (let [{:keys [id actor payload]} job
        err-msg (str
                  "Error from actor `" actor
                  "`, payload `" payload
                  "`: " (.getMessage e)
                  "\n" (str/join "\n" (map str (.getStackTrace e))))]
    (log/error e err-msg)
    (job/set-job-last-error!
      db
      {:id id
       :last_error err-msg})))

(defn ^:no-doc work-loop
  "The main work loop for a queue worker."
  [db queue-name qdef stop-ch]
  (let [{:keys [max-attempts
                timeout
                max-timeout
                backoff-factor
                poll-interval]} qdef]
    (loop []
      ;; Try and pick the next ready job for this queue. If a job is picked,
      ;; it will also be rescheduled immediately for a retry to guarantee
      ;; at-least-once run semantics.
      ;; Also check `stop-ch` to see if we have been asked to terminate.
      (let [v (async/alt!!
                stop-ch :stopped
                :default (job/pick-job-by-queue!
                           db
                           {:queue_name queue-name
                            :base_timeout timeout
                            :max_timeout max-timeout
                            :backoff_factor backoff-factor})
                :priority true)]
        (when-not (= v :stopped)
          (do
            (if-let [{:keys [id actor payload attempt last_error interval]} v]
              ;; A job ready for running was picked
              (if (> attempt max-attempts)
                ;; The job has exceeded its maximum number of retries. Log the
                ;; failure and either delete the job (if it's not a recurring
                ;; job), or re-enqueue it with its next execution time
                ;; otherwise.
                (do
                  (log/error
                    (str
                      "Job " id " for actor " actor
                      " has failed too many times (" max-attempts "). Last error:"
                      "\n" last_error))
                  (if interval
                    (job/reenqueue-and-fail-job!
                      db
                      {:id id
                       :run_at (util/->offset-time-zulu
                                 (cron/next-run interval))})
                    (job/delete-and-fail-job! db {:id id}))
                  (job/inc-queue-fail! db {:queue_name queue-name, :retries max-attempts}))
                ;; Run the actor function with the specified arguments and, if
                ;; successful, either delete the job (if it's not a recurring
                ;; job), or re-enqueue it with its next execution time
                ;; otherwise.
                (try
                  (let [f (util/str->fnvar actor)
                        args (edn/read-string payload)]
                    (apply f args)
                    (if interval
                      (job/reenqueue-job!
                        db {:id id
                            :run_at (util/->offset-time-zulu
                                      (cron/next-run interval))})
                      (job/delete-job! db {:id id}))
                    (job/inc-queue-success! db {:queue_name queue-name, :retries attempt}))
                  ;; If the job fails with an Exception, log the error but
                  ;; otherwise don't do anything. If we are here, the job
                  ;; has not exceeded yet its retries, and another retry for
                  ;; it will have already been scheduled at pick time.
                  (catch Exception e
                    (-log-worker-error db v e))
                  (catch AssertionError e
                    (-log-worker-error db v e))))
              ;; No job ready for running was found, so back off for
              ;; at most `poll-interval` seconds before trying again.
              ;; Keep checking `stop-ch` to see if we have been asked
              ;; to terminate.
              (let [wait-secs (rand-int poll-interval)]
                (async/alt!!
                  stop-ch :stopped
                  (async/timeout (* wait-secs 1000)) :timed-out)))
            (recur)))))))

(defn ^:no-doc start-queue-threads [context queue-name qdef stop-ch]
  (let [{:keys [num-threads]} qdef
        {:keys [db]} context]
    (repeatedly
      num-threads
      (fn []
        (async/thread
          (loop [exited? false]
            (when-not exited?
              (recur
                (try
                  (do
                    (work-loop db queue-name qdef stop-ch)
                    true)
                  (catch Exception e
                    (log/error e "Unexpected exception in worker loop, restarting.")
                    false)
                  (catch AssertionError e
                    (log/error e "Unexpected exception in worker loop, restarting.")
                    false))))))))))
