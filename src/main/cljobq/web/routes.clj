(ns cljobq.web.routes
  (:require
    [compojure.core :refer [routes GET POST]]
    [compojure.coercions :refer [as-int]]
    [cljobq.web.controllers.core :as c]
    [hiccup.util :refer [url to-str]]
    [ring.util.response :refer [redirect]]))

(def routes-handler
  (routes
    (GET "/"
         [] (redirect (to-str (url "/all"))))
    (GET "/all"
         [queue :as r] (c/jobs-controller :all r queue))
    (GET "/queued"
         [queue :as r] (c/jobs-controller :queued r queue))
    (GET "/scheduled"
         [queue :as r] (c/jobs-controller :scheduled r queue))
    (GET "/failed"
         [queue :as r] (c/failed-jobs-controller r queue))
    (GET "/recurring"
         [queue :as r] (c/recurring-jobs-controller r queue))
    (GET "/job/:id"
         [id :<< as-int :as r] (c/job-controller r id))
    (GET "/failed/:id"
         [id :<< as-int :as r] (c/failed-job-controller r id))
    (POST "/failed/clear"
          [queue :as r] (c/clear-failed-controller r queue))
    (POST "/failed/:id/delete"
          [id :<< as-int :as r] (c/delete-failed-controller r id))
    (POST "/failed/:id/retry"
          [id :<< as-int :as r] (c/retry-failed-controller r id))
    (POST "/job/:id/run"
          [id :<< as-int :as r] (c/run-job-controller r id))))
