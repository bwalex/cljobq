(ns cljobq.job
  (:require
    [clojure.edn :as edn]
    [hugsql.core :as hugsql]))

(hugsql/def-db-fns "sql/cljobq.sql")

(defn dbjob->friendly-job [dbjob]
  {:id (:id dbjob)
   :queue (:queue_name dbjob)
   :actor (:actor dbjob)
   :args (-> dbjob :payload edn/read-string)
   :job-name (:job dbjob)
   :related-job-id (:job_id dbjob)
   :run-at (:run_at dbjob)
   :interval (:interval dbjob)
   :created-at (:created_at dbjob)
   :picked-at (:picked_at dbjob)
   :last-error (:last_error dbjob)
   :status (:status dbjob)
   :attempt (:attempt dbjob)})

(defn- qstats-reducer
  ([]
   {:success 0
    :fail    0
    :retries 0})
  ([a b]
   {:success (+ (:success a) (:success b))
    :fail    (+ (:fail a)    (:fail b))
    :retries (+ (:retries a) (:retries b))}))

(defn- jstats-reducer
  ([]
   {:active-count 0
    :pending      0
    :scheduled    0})
  ([a b]
   {:active-count (+ (:count a)     (:count b))
    :pending      (+ (:pending a)   (:pending b))
    :scheduled    (+ (:scheduled a) (:scheduled b))}))

(defn get-stats
  ([db] (get-stats db nil))
  ([db queue]
   (let [queue-stats (get-queue-stats db)
         job-stats   (get-job-stats db)
         qstat (cond->> queue-stats
                 queue (filter #(= (:queue_name %) queue))
                 true  (reduce qstats-reducer))
         jstat (cond->> job-stats
                 queue (filter #(= (:queue_name %) queue))
                 true  (reduce jstats-reducer))]
     (merge
       qstat
       jstat
       {:done-count (+ (:success qstat) (:fail qstat))}))))

(defn list-jobs
  ([db] (list-jobs db nil))
  ([db queue]
   (->> (if queue
          (get-jobs-by-queue db {:queue_name queue})
          (get-jobs db))
        (map dbjob->friendly-job))))

(defn list-failed-jobs
  ([db] (list-failed-jobs db nil))
  ([db queue]
   (->> (if queue
          (get-failed-jobs-by-queue db {:queue_name queue})
          (get-failed-jobs db))
        (map dbjob->friendly-job))))

(defn list-recurring-jobs
  ([db] (list-recurring-jobs db nil))
  ([db queue]
   (cond->> (get-recurring-jobs db)
     queue (filter #(= queue (:queue %)))
     true  (map dbjob->friendly-job))))

(defn list-queue-names [db]
  (let [queue-stats (get-queue-stats db)
        job-stats   (get-job-stats db)]
    (->> (concat queue-stats job-stats)
         (map :queue_name)
         (apply sorted-set))))
