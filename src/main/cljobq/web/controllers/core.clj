(ns cljobq.web.controllers.core
  (:require
    [cljobq.web.views.core :as views]
    [cljobq.web.middleware :as middleware]
    [cljobq.job :as cj]
    [hiccup.util :refer [url to-str]]
    [ring.util.response :refer [redirect]]))

(defn jobs-controller [filt request queue]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        queue-names (cj/list-queue-names db)
        stats (cj/get-stats db queue)
        jobs (cond->> (cj/list-jobs db queue)
               (= filt :queued)    (filter #(= (:status %) "pending"))
               (= filt :scheduled) (filter #(= (:status %) "scheduled")))]
    (views/job-list
      {:filt filt
       :request request
       :flash (:flash request)
       :queue queue
       :queue-names queue-names
       :stats stats
       :jobs  jobs})))

(defn failed-jobs-controller [request queue]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        queue-names (cj/list-queue-names db)
        stats (cj/get-stats db queue)
        jobs (cj/list-failed-jobs db queue)]
    (views/failed-job-list
      {:flash (:flash request)
       :request request
       :queue queue
       :queue-names queue-names
       :stats stats
       :jobs  jobs})))

(defn recurring-jobs-controller [request queue]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        queue-names (cj/list-queue-names db)
        stats (cj/get-stats db queue)
        jobs (cj/list-recurring-jobs db queue)]
    (views/recurring-job-list
      {:flash (:flash request)
       :request request
       :queue queue
       :queue-names queue-names
       :stats stats
       :jobs  jobs})))

(defn job-controller [request id]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        job (-> (cj/get-job-by-id db {:id id})
                cj/dbjob->friendly-job)]
    (views/single-job
      {:flash (:flash request)
       :request request
       :job job})))

(defn failed-job-controller [request id]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        job (-> (cj/get-failed-job-by-id db {:id id})
                cj/dbjob->friendly-job)
        related-job (some->> (:related-job-id job)
                             (cj/get-job-by-id db)
                             cj/dbjob->friendly-job)]
    (views/single-failed-job
      {:flash (:flash request)
       :request request
       :job job
       :related-job related-job})))

(defn run-job-controller [request id]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        job (-> (cj/get-job-by-id db {:id id})
                cj/dbjob->friendly-job)
        {:keys [queue job-name]} job]
    (cj/reenqueue-job! db {:id id})
    (-> (redirect (to-str (url "/queued" {:queue queue})))
        (assoc :flash
               (str "Job "
                    job-name
                    " ["
                    queue
                    "] scheduled to run immediately.")))))

(defn retry-failed-controller [request id]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        job (-> (cj/get-failed-job-by-id db {:id id})
                cj/dbjob->friendly-job)
        {:keys [queue job-name]} job]
    (cj/retry-failed-job! db {:id id})
    (-> (redirect (to-str (url "/queued" {:queue queue})))
        (assoc :flash
               (str "Job "
                    job-name
                    " ["
                    queue
                    "] scheduled to retry immediately.")))))

(defn delete-failed-controller [request id]
  (let [ctx (::middleware/context request)
        db (:db ctx)
        job (-> (cj/get-failed-job-by-id db {:id id})
                cj/dbjob->friendly-job)
        {:keys [queue job-name]} job]
    (cj/delete-failed-job-by-id! db {:id id})
    (-> (redirect (to-str (url "/failed" {:queue queue})))
        (assoc :flash
               (str "Failed job "
                    job-name
                    " ["
                    queue
                    "] deleted.")))))

(defn clear-failed-controller [request queue]
  (let [ctx (::middleware/context request)
        db (:db ctx)]
    (if queue
      (cj/reset-failed-jobs-by-queue! db {:queue queue})
      (cj/reset-failed-jobs! db))
    (-> (redirect (to-str (url "/failed" {:queue queue})))
        (assoc :flash "Failed jobs cleared."))))
