CREATE TABLE cljobq_jobs (
  id            BIGSERIAL PRIMARY KEY,
  queue_name    VARCHAR(128) NOT NULL,
  job           TEXT,
  actor         VARCHAR(512),
  payload       TEXT,
  interval      TEXT,
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT now(),
  run_at        TIMESTAMP WITH TIME ZONE DEFAULT now(),
  picked_at     TIMESTAMP WITH TIME ZONE,
  attempt       INTEGER                  DEFAULT 0,
  last_error    TEXT
);
--;;
CREATE INDEX cljobq_jobs_pri_idx
  ON cljobq_jobs (queue_name, run_at, id DESC);
--;;
ALTER TABLE ONLY cljobq_jobs
  ADD CONSTRAINT cljobq_jobs_queue_job_uniq UNIQUE (queue_name, job);
--;;
CREATE TABLE cljobq_failed_jobs (
  id            BIGSERIAL PRIMARY KEY,
  job_id        BIGINT REFERENCES cljobq_jobs(id) ON DELETE SET NULL,
  queue_name    VARCHAR(128) NOT NULL,
  job           TEXT,
  actor         VARCHAR(512),
  payload       TEXT,
  created_at    TIMESTAMP WITH TIME ZONE DEFAULT now(),
  picked_at     TIMESTAMP WITH TIME ZONE NOT NULL,
  attempt       INTEGER                  DEFAULT 0,
  last_error    TEXT
);
--;;
CREATE OR REPLACE FUNCTION cljobq_delete_old_fails() RETURNS TRIGGER
AS $$
  BEGIN
    DELETE FROM cljobq_failed_jobs WHERE picked_at < now() - interval '30 days';
    RETURN NULL;
  END;
$$ LANGUAGE plpgsql;
--;;
CREATE TRIGGER cljobq_failed_trigger_delete_old
  AFTER INSERT ON cljobq_failed_jobs
  EXECUTE PROCEDURE cljobq_delete_old_fails();
--;;
CREATE TABLE cljobq_qstats (
  id            BIGSERIAL PRIMARY KEY,
  queue_name    VARCHAR(128) NOT NULL CONSTRAINT cljobq_qstats_queue_uniq UNIQUE,
  success       BIGINT DEFAULT 0,
  fail          BIGINT DEFAULT 0,
  retries       BIGINT DEFAULT 0
);
--;;
CREATE INDEX cljobq_qstats_pri_idx
  ON cljobq_qstats (queue_name);
-- XXX: consider more granular qstats; hourly for a week, daily for a month, aggregate after that
