(ns cljobq.web.standalone
  (:require
    [cljobq.web :refer [make-handler]]
    [clojure.string :as str]
    [clojure.tools.cli :refer [parse-opts]]
    [clojure.tools.logging :as log]
    [io.aviso.logging.setup]
    [org.httpkit.server :as http-kit]
    [to-jdbc-uri.core :refer [to-jdbc-uri]])
  (:gen-class))

(def cli-options
  [["-d" "--database-uri URI" "Database URI to connect to cljobq. Required."
    :id :database-uri
    :parse-fn #(to-jdbc-uri %)]
   ["-l" "--listen-ip IP" "IP address to listen on"
    :id :listen-ip
    :default "0.0.0.0"
    :parse-fn #(str %)]
   ["-p" "--listen-port PORT" "Port number"
    :id :listen-port
    :default 4480
    :parse-fn #(Integer/parseInt %)
    :validate [#(< 0 % 0x10000) "Must be a number between 0 and 65535"]]
   ["-b" "--base-url URL" "Base URL under which cljobq-web is being fronted"
    :id :base-url
    :default nil
    :parse-fn #(str %)]
   ["-h" "--help"]])

(def required-opts #{:database-uri})

(defn missing-required?
  "Returns true if opts is missing any of the required-opts"
  [opts]
  (not-every? opts required-opts))

(defn usage [options-summary]
  (->> ["Usage: cljobq-web-server [options]"
        ""
        "Options:"
        options-summary]
       (str/join \newline)))

(defn error-msg [errors options-summary]
  (str (str/join \newline errors)
       "\n"
       (usage options-summary)))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)
        {:keys [database-uri listen-ip listen-port base-url]} options]
    (cond
      (:help options)             (exit 0 (usage summary))
      (missing-required? options) (exit 1 (usage summary))
      errors                      (exit 1 (error-msg errors summary)))
    (log/info (str "Starting HTTP server on [" listen-ip "]:" listen-port))
    (http-kit/run-server
      (make-handler
        {:db {:connection-uri database-uri}
         :base-url base-url})
      {:ip listen-ip
       :port listen-port
       :thread 2})))
