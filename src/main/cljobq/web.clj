(ns cljobq.web
  (:require
    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.flash :refer [wrap-flash]]
    [cljobq.web.middleware :refer [wrap-context]]
    [cljobq.web.routes :refer [routes-handler]]))


(def ring-defaults
  (-> site-defaults
      (assoc-in [:session :flash] false)
      (assoc-in [:static :resources] "cljobq/web/assets")))

(defn make-handler
  "Returns a Ring handler function providing the cljobq web UI.

  Options:

  - `:db`        A `clojure.java.jdbc` db-spec map. If specified, it is used
                 in preference to the db-spec in the `context`.
  - `:context`   cljobq context created via `cljobq.core/make-context`. If not
                 specified, the implicit `cljobq.core/global-ctx*` atom set
                 using `cljobq.core/set-context!` is used instead. If `:db` is
                 specified, that is used instead of any db-spec in the
                 specified context.
  - `:base-url`  Specify a base url under which cljobq.web is being served.
                 This affects the generated HTML only, e.g. affects links,
                 CSS and JS urls. If omitted, assumes relative links such as
                 `/job/abc` will find their way to cljobq.web. If, however,
                 cljobq.web is being fronted under, say, `/cljobq`,
                 `:base-url` should be set to that.
  - `:ring-defaults` Specify a custom ring.middleware.defaults configuration.

  Example:

  ```clojure
  (def cljobq-handler
    (cljobq.web/make-handler
      {:db {:connection-uri \"jdbc:postgresql://localhost:5432/jobqtest?user=test&password=test\"}))
  ```"
  ([] (make-handler {}))
  ([config]
   (-> routes-handler
       (wrap-context config)
       (wrap-flash)
       (wrap-defaults
         (-> (get config :ring-defaults ring-defaults)
             (assoc-in [:static :resources] "cljobq/web/assets"))))))
