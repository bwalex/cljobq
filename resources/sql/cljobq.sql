-- https://blog.2ndquadrant.com/what-is-select-skip-locked-for-in-postgresql-9-5/

-- :name pick-job-by-queue! :? :1
-- :doc "Abc"
WITH cte AS (
  SELECT id FROM cljobq_jobs
  WHERE queue_name = :queue_name
    AND run_at <= now()
  FOR UPDATE SKIP LOCKED
  LIMIT 1
)
UPDATE cljobq_jobs q
SET
  run_at  = now() +
            cast(
              random() * :base_timeout +
              least(:base_timeout * power(:backoff_factor, attempt),
                    :max_timeout)
	      as integer
            ) * interval '1 second',
  picked_at = now(),
  attempt = attempt + 1
FROM cte
WHERE q.id = cte.id
RETURNING q.*,
  CASE WHEN q.run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status

-- :name insert-job! :<! :1
INSERT INTO cljobq_jobs (queue_name, job, actor, payload, interval, run_at)
VALUES (:queue_name, :job, :actor, :payload, :interval, :run_at)
RETURNING *

-- :name upsert-job! :<! :1
INSERT INTO cljobq_jobs (queue_name, job, actor, payload, interval, run_at)
VALUES (:queue_name, :job, :actor, :payload, :interval, :run_at)
ON CONFLICT ON CONSTRAINT cljobq_jobs_queue_job_uniq DO UPDATE
SET
  actor = :actor,
  payload = :payload,
  interval = :interval,
  run_at = :run_at
RETURNING *

-- :name delete-job! :! :n
DELETE FROM cljobq_jobs WHERE id = :id

-- :name delete-and-fail-job! :! :n
WITH cte AS (
  DELETE FROM cljobq_jobs q
  WHERE id = :id
  RETURNING q.*
)
INSERT INTO cljobq_failed_jobs (queue_name, job, actor, payload, created_at, picked_at, attempt, last_error)
SELECT queue_name, job, actor, payload, created_at, picked_at, attempt, last_error FROM cte

-- :name reenqueue-job! :! :n
UPDATE cljobq_jobs
SET
/*~ (if (:run_at params) */
  run_at = :run_at,
/*~*/
  run_at = now(),
/*~ ) ~*/
  picked_at = NULL,
  attempt = 0,
  last_error = NULL
WHERE id = :id

-- :name reenqueue-and-fail-job! :! :n
WITH cte AS (
  WITH cte2 AS (
    SELECT * FROM cljobq_jobs WHERE id = :id FOR UPDATE
  )
  UPDATE cljobq_jobs q
  SET
    run_at = :run_at,
    picked_at = NULL,
    attempt = 0,
    last_error = NULL
  FROM cte2
  WHERE q.id = cte2.id
  RETURNING cte2.*
)
INSERT INTO cljobq_failed_jobs (job_id, queue_name, job, actor, payload, created_at, picked_at, attempt, last_error)
SELECT id, queue_name, job, actor, payload, created_at, picked_at, attempt, last_error FROM cte

-- :name retry-failed-job! :<! :1
WITH cte AS (
  DELETE FROM cljobq_failed_jobs q
  WHERE id = :id
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
RETURNING *

-- :name set-job-last-error! :! :n
UPDATE cljobq_jobs
SET
  last_error = :last_error
WHERE id = :id

-- :name delete-job-by-name! :! :n
DELETE FROM cljobq_jobs WHERE queue_name = :queue_name AND job = :job

-- :name delete-queue-jobs! :! :n
DELETE FROM cljobq_jobs WHERE queue_name = :queue_name

-- :name get-jobs :? :*
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs
ORDER BY run_at ASC

-- :name get-jobs-by-queue :? :*
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs WHERE queue_name = :queue_name
ORDER BY run_at ASC

-- :name get-jobs-with-error :? :*
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs WHERE last_error IS NOT NULL
ORDER BY run_at ASC

-- :name get-recurring-jobs :? :*
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs WHERE interval IS NOT NULL
ORDER BY job ASC

-- :name get-job-by-id :? :1
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs WHERE id = :id

-- :name get-job-by-name :? :1
SELECT *,
  CASE WHEN run_at <= now() THEN 'pending'
       ELSE 'scheduled'
  END AS status
FROM cljobq_jobs WHERE queue_name = :queue_name AND job = :job

-- :name get-failed-jobs :? :*
SELECT * FROM cljobq_failed_jobs
ORDER BY picked_at ASC

-- :name get-failed-job-by-id :? :1
SELECT * FROM cljobq_failed_jobs WHERE id = :id

-- :name get-failed-jobs-by-queue :? :*
SELECT * FROM cljobq_failed_jobs WHERE queue_name = :queue_name
ORDER BY picked_at ASC

-- :name delete-failed-job-by-id! :! :n
DELETE FROM cljobq_failed_jobs WHERE id = :id

-- :name reset-failed-jobs-by-queue! :! :n
DELETE FROM cljobq_failed_jobs WHERE queue_name = :queue_name

-- :name reset-failed-jobs! :! :n
DELETE FROM cljobq_failed_jobs

-- :name inc-queue-success! :! :n
INSERT INTO cljobq_qstats (queue_name, success, retries)
VALUES (:queue_name, 1, :retries)
ON CONFLICT ON CONSTRAINT cljobq_qstats_queue_uniq DO UPDATE
SET
  success = cljobq_qstats.success + 1,
  retries = cljobq_qstats.retries + :retries

-- :name inc-queue-fail! :! :n
INSERT INTO cljobq_qstats (queue_name, fail, retries)
VALUES (:queue_name, 1, :retries)
ON CONFLICT ON CONSTRAINT cljobq_qstats_queue_uniq DO UPDATE
SET
  fail    = cljobq_qstats.fail + 1,
  retries = cljobq_qstats.retries + :retries

-- :name reset-queue-stats! :! :n
UPDATE cljobq_qstats
SET
  success = 0,
  fail = 0,
  retries = 0
WHERE queue_name = :queue_name

-- :name reset-stats! :! :n
DELETE FROM cljobq_qstats

-- :name get-queue-stats :? :*
SELECT queue_name, success, fail, retries FROM cljobq_qstats

-- :name get-job-stats :? :*
SELECT
  queue_name,
  COUNT(*) as count,
  COUNT(CASE WHEN run_at <= now() THEN 1 END) as pending,
  COUNT(CASE WHEN run_at >  now() THEN 1 END) as scheduled
FROM cljobq_jobs
GROUP BY queue_name
