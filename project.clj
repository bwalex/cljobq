(defproject cljobq "0.1.2"
  :description "A simple PostgreSQL-based job queue"
  :url "https://github.com/bwalex/cljobq"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[clojure.java-time "0.3.2"]
                 [com.layerware/hugsql "0.4.9"]
                 [com.cronutils/cron-utils "7.0.2"]
                 [danlentz/clj-uuid "0.1.7"]
                 [migratus "0.9.8"]
                 [org.clojure/clojure "1.9.0"]
                 [org.clojure/core.async "0.4.474"]
                 [org.clojure/tools.logging "0.4.0"]]

  :plugins [[migratus-lein "0.5.0"]
            [lein-eftest "0.5.1"]
            [jonase/eastwood "0.2.6-beta2"]
            [lein-codox "0.10.4"]
            [lein-kibit "0.1.6"]]
  :codox {:metadata {:doc/format :markdown}}
  :profiles
  {:dev {:dependencies [[org.slf4j/slf4j-log4j12 "1.7.9"]
                        [org.postgresql/postgresql "42.1.4"]]}})
