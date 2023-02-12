(ns cljobq.job
  (:require
    [clojure.edn :as edn]
    [clojure.java.jdbc :as jdbc]))


(defn identifiers
  [x]
  (case x
    "job"        :job-name
    "queue_name" :queue
    "last_error" :last-error
    "created_at" :created-at
    "run_at"     :run-at
    "picked_at"  :picked-at
    "job_id"     :related-job-id
    (keyword x)))

(defn dbjob->friendly-job [dbjob]
  (assoc dbjob :args (-> dbjob :payload edn/read-string)))

;; https://blog.2ndquadrant.com/what-is-select-skip-locked-for-in-postgresql-9-5/
(defn pick-job-by-queue!
  [db {:keys [queue base-timeout backoff-factor max-timeout]}]
  (first
    (jdbc/query
      db
      ["WITH cte AS (
          SELECT id FROM cljobq_jobs
          WHERE queue_name = ?
            AND run_at <= now()
          FOR UPDATE SKIP LOCKED
          LIMIT 1
        )
        UPDATE cljobq_jobs q
        SET
          run_at  = now() +
                    cast(
                      random() * ? +
                      least(? * power(?, attempt), ?)
                      as integer
                    ) * interval '1 second',
          picked_at = now(),
          attempt = attempt + 1
        FROM cte
        WHERE q.id = cte.id
        RETURNING q.*,
          CASE WHEN q.run_at <= now() THEN 'pending'
               ELSE 'scheduled'
          END AS status"
       queue, base-timeout, base-timeout, backoff-factor, max-timeout]
      {:identifiers identifiers})))

(defn insert-job!
  [db {:keys [queue job-name actor payload interval run-at]}]
  (first
    (jdbc/query
      db
      ["INSERT INTO cljobq_jobs (queue_name, job, actor, payload, interval, run_at)
        VALUES (?, ?, ?, ?, ?, ?)
        RETURNING *"
       queue, job-name, actor, payload, interval, run-at]
      {:identifiers identifiers})))

(defn upsert-job!
  [db {:keys [queue job-name actor payload interval run-at]}]
  (first
    (jdbc/query
      db
      ["INSERT INTO cljobq_jobs (queue_name, job, actor, payload, interval, run_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT ON CONSTRAINT cljobq_jobs_queue_job_uniq DO UPDATE
        SET
          actor = ?,
          payload = ?,
          interval = ?,
          run_at = ?
        RETURNING *"
       queue job-name actor payload interval run-at
       actor payload interval run-at]
      {:identifiers identifiers})))

(defn delete-job!
  [db {:keys [id]}]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_jobs WHERE id = ?" id])))

(defn delete-and-fail-job!
  [db {:keys [id]}]
  (first
    (jdbc/execute!
      db
      ["WITH cte AS (
          DELETE FROM cljobq_jobs q
          WHERE id = ?
          RETURNING q.*
        )
        INSERT INTO cljobq_failed_jobs (queue_name, job, actor, payload, created_at, picked_at, attempt, last_error)
        SELECT queue_name, job, actor, payload, created_at, picked_at, attempt, last_error FROM cte"
       id])))

(defn reenqueue-job!
  [db {:keys [id run-at]}]
  (first
    (jdbc/execute!
      db
      (if run-at
        ["UPDATE cljobq_jobs SET
            run_at = ?,
            picked_at = NULL,
            attempt = 0,
            last_error = NULL
          WHERE id = ?"
         run-at id]
        ["UPDATE cljobq_jobs
          SET
            run_at = now(),
            picked_at = NULL,
            attempt = 0,
            last_error = NULL
          WHERE id = ?"
         id]))))

(defn reenqueue-and-fail-job!
  [db {:keys [id run-at]}]
  (first
    (jdbc/execute!
      db
      ["WITH cte AS (
          WITH cte2 AS (
            SELECT * FROM cljobq_jobs WHERE id = ? FOR UPDATE
          )
          UPDATE cljobq_jobs q
          SET
            run_at = ?,
            picked_at = NULL,
            attempt = 0,
            last_error = NULL
          FROM cte2
          WHERE q.id = cte2.id
          RETURNING cte2.*
        )
        INSERT INTO cljobq_failed_jobs (job_id, queue_name, job, actor, payload, created_at, picked_at, attempt, last_error)
        SELECT id, queue_name, job, actor, payload, created_at, picked_at, attempt, last_error FROM cte"
       id run-at])))

(defn retry-failed-job!
  [db {:keys [id]}]
  (first
    (jdbc/query
      db
      ["WITH cte AS (
          DELETE FROM cljobq_failed_jobs q
          WHERE id = ?
          RETURNING q.*
        )
        INSERT INTO cljobq_jobs (queue_name, job, actor, payload, created_at)
        SELECT queue_name, job, actor, payload, created_at FROM cte
        ON CONFLICT ON CONSTRAINT cljobq_jobs_queue_job_uniq DO UPDATE
        SET
          run_at = now(),
          picked_at = NULL,
          attempt = 0,
          last_error = NULL
        RETURNING *"
       id]
      {:identifiers identifiers})))

(defn set-job-last-error!
  [db {:keys [last-error id]}]
  (first
    (jdbc/execute!
      db
      ["UPDATE cljobq_jobs
        SET
          last_error = ?
        WHERE id = ?"
       last-error id])))

(defn delete-job-by-name!
  [db {:keys [queue job-name]}]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_jobs WHERE queue_name = ? AND job = ?"
       queue job-name])))

(defn delete-queue-jobs!
  [db {:keys [queue]}]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_jobs WHERE queue_name" queue])))

(defn get-jobs
  [db]
  (jdbc/query
    db
    ["SELECT *,
        CASE WHEN run_at <= now() THEN 'pending'
             ELSE 'scheduled'
        END AS status
      FROM cljobq_jobs
      ORDER BY run_at ASC"]
    {:identifiers identifiers}))

(defn get-jobs-by-queue
  [db {:keys [queue]}]
  (jdbc/query
    db
    ["SELECT *,
        CASE WHEN run_at <= now() THEN 'pending'
             ELSE 'scheduled'
        END AS status
      FROM cljobq_jobs WHERE queue_name = ?
      ORDER BY run_at ASC"
     queue]
    {:identifiers identifiers}))

(defn get-jobs-with-error
  [db]
  (jdbc/query
    db
    ["SELECT *,
        CASE WHEN run_at <= now() THEN 'pending'
             ELSE 'scheduled'
        END AS status
      FROM cljobq_jobs WHERE last_error IS NOT NULL
      ORDER BY run_at ASC"]
    {:identifiers identifiers}))

(defn get-recurring-jobs
  [db]
  (jdbc/query
    db
    ["SELECT *,
        CASE WHEN run_at <= now() THEN 'pending'
             ELSE 'scheduled'
        END AS status
      FROM cljobq_jobs WHERE interval IS NOT NULL
      ORDER BY job ASC"]
    {:identifiers identifiers}))

(defn get-job-by-id
  [db {:keys [id]}]
  (first
    (jdbc/query
      db
      ["SELECT *,
          CASE WHEN run_at <= now() THEN 'pending'
               ELSE 'scheduled'
          END AS status
        FROM cljobq_jobs WHERE id = ?"
       id]
      {:identifiers identifiers})))

(defn get-job-by-name
  [db {:keys [queue job-name]}]
  (first
    (jdbc/query
      db
      ["SELECT *,
          CASE WHEN run_at <= now() THEN 'pending'
               ELSE 'scheduled'
          END AS status
        FROM cljobq_jobs WHERE queue_name = ? AND job = ?"
       queue job-name]
      {:identifiers identifiers})))

(defn get-failed-jobs
  [db]
  (jdbc/query
    db
    ["SELECT * FROM cljobq_failed_jobs ORDER BY picked_at ASC"]
    {:identifiers identifiers}))

(defn get-failed-job-by-id
  [db {:keys [id]}]
  (first
    (jdbc/query
      db
      ["SELECT * FROM cljobq_failed_jobs WHERE id = ?" id]
      {:identifiers identifiers})))

(defn get-failed-jobs-by-queue
  [db {:keys [queue]}]
  (jdbc/query
    db
    ["SELECT * FROM cljobq_failed_jobs WHERE queue_name = ?
      ORDER BY picked_at ASC"
     queue]
    {:identifiers identifiers}))

(defn delete-failed-job-by-id!
  [db {:keys [id]}]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_failed_jobs WHERE id = ?" id])))

(defn reset-failed-jobs-by-queue!
  [db {:keys [queue]}]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_failed_jobs WHERE queue_name = ?" queue])))

(defn reset-failed-jobs!
  [db]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_failed_jobs"])))

(defn inc-queue-success!
  [db {:keys [queue retries]}]
  (first
    (jdbc/execute!
      db
      ["INSERT INTO cljobq_qstats (queue_name, success, retries)
        VALUES (?, 1, ?)
        ON CONFLICT ON CONSTRAINT cljobq_qstats_queue_uniq DO UPDATE
        SET
          success = cljobq_qstats.success + 1,
          retries = cljobq_qstats.retries + ?"
       queue retries retries])))

(defn inc-queue-fail!
  [db {:keys [queue retries]}]
  (first
    (jdbc/execute!
      db
      ["INSERT INTO cljobq_qstats (queue_name, fail, retries)
        VALUES (?, 1, ?)
        ON CONFLICT ON CONSTRAINT cljobq_qstats_queue_uniq DO UPDATE
        SET
          fail    = cljobq_qstats.fail + 1,
          retries = cljobq_qstats.retries + ?"
       queue retries retries])))

(defn reset-queue-stats!
  [db {:keys [queue]}]
  (first
    (jdbc/execute!
      db
      ["UPDATE cljobq_qstats
        SET
          success = 0,
          fail = 0,
          retries = 0
        WHERE queue_name = ?"
       queue])))

(defn reset-stats!
  [db]
  (first
    (jdbc/execute!
      db
      ["DELETE FROM cljobq_qstats"])))

(defn get-queue-stats
  [db]
  (jdbc/query
    db
    ["SELECT queue_name, success, fail, retries FROM cljobq_qstats"]
    {:identifiers identifiers}))

(defn get-job-stats
  [db]
  (jdbc/query
    db
    ["SELECT
        queue_name,
        COUNT(*) as count,
        COUNT(CASE WHEN run_at <= now() THEN 1 END) as pending,
        COUNT(CASE WHEN run_at >  now() THEN 1 END) as scheduled
      FROM cljobq_jobs
      GROUP BY queue_name"]
    {:identifiers identifiers}))

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
                 queue (filter #(= (:queue %) queue))
                 true  (reduce qstats-reducer))
         jstat (cond->> job-stats
                 queue (filter #(= (:queue %) queue))
                 true  (reduce jstats-reducer))]
     (merge
       qstat
       jstat
       {:done-count (+ (:success qstat) (:fail qstat))}))))

(defn list-jobs
  ([db] (list-jobs db nil))
  ([db queue]
   (->> (if queue
          (get-jobs-by-queue db {:queue queue})
          (get-jobs db))
        (map dbjob->friendly-job))))

(defn list-failed-jobs
  ([db] (list-failed-jobs db nil))
  ([db queue]
   (->> (if queue
          (get-failed-jobs-by-queue db {:queue queue})
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
         (map :queue)
         (apply sorted-set))))
