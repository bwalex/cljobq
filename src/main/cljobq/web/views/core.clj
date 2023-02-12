(ns cljobq.web.views.core
  (:require
    [hiccup.core :refer [html]]
    [hiccup.form :refer [hidden-field]]
    [hiccup.util :refer [url escape-html]]))

(defn head [title]
  [:head {}
   [:link {:rel "stylesheet", :href (url "/spectre.min.css")}]
   [:link {:rel "stylesheet", :href (url "/selectize.css")}]
   [:link {:rel "stylesheet", :href (url "/style.css")}]
   [:title {}
    (if title
      (str title " - cljobq")
      "cljobq")]])

(def navbar-links
  [{:key :all-jobs
    :text "All jobs"
    :url "/all"}
   {:key :queued-jobs
    :text "Queued"
    :url "/queued"}
   {:key :scheduled-jobs
    :text "Scheduled"
    :url "/scheduled"}
   {:key :failed-jobs
    :text "Failed"
    :url "/failed"}
   {:key :recurring-jobs
    :text "Recurring"
    :url "/recurring"}])

(defn navbar [active-key]
  [:header.navbar {:style "margin-bottom: 16px;"}
   [:section.navbar-section {}
    [:span.navbar-logo.text-primary {} "cljobq"]]
   [:section.navbar-section {}
    (for [l navbar-links]
      [:a.navbar-link {:href (url (:url l))
                       :class (when (= (:key l) active-key)
                                "selected")}
       (:text l)])]])

(defn breadcrumbs
  ([crumbs] (breadcrumbs crumbs nil))
  ([crumbs right-elem]
   [:div.columns {}
    [:div.column.col-8.col-mr-auto {}
     [:ul.breadcrumb {}
      (for [v crumbs]
        [:li.breadcrumb-item {}
         [:a {:href (:url v)} (:title v)]])]]
    [:div.column.col-3.col-ml-auto {}
     right-elem]]))

(def stats
  [{:key :done-count
    :label "Finished"}
   {:key :success
    :label "Success"}
   {:key :fail
    :label "Fail"}
   {:key :retries
    :label "Retries"}
   {:key :pending
    :label "Queued"}
   {:key :scheduled
    :label "Scheduled"}])

(defn queue-select
  ([queue-names] (queue-select queue-names nil))
  ([queue-names active-queue]
   [:select#queue-pick {}
    [:option {:selected (nil? active-queue), :value "::none"} "All queues"]
    (for [q queue-names]
      [:option
       {:selected (= q active-queue)
        :value q}
       q])]))

(defn stats-grid [v]
  [:div.stats-grid {}
   (for [s stats]
     [:span.stats-value {} (get v (:key s) 0)])
   (for [s stats]
     [:span.stats-label {} (:label s)])])

(defn form-link-button [{:keys [anti-forgery-token] :as _request}
                        action label]
  [:form {:action action, :method "POST", :style "display: inline;"}
   (hidden-field "__anti-forgery-token" anti-forgery-token)
   [:button.btn.btn-link {} label]])

(defn job-columns [request]
  [{:label "Queue"
    :render-fn :queue}
   {:label "Job name"
    :render-fn
    (fn [{:keys [id job-name]}]
      [:a {:href (url "/job/" id)} job-name])}
   {:label "Actor"
    :render-fn :actor}
   {:label "Args"
    :render-fn (comp escape-html str :args)}
   {:label "Retries"
    :render-fn :attempt}
   {:label "Last picked"
    :render-fn :picked-at}
   {:label "Scheduled for"
    :render-fn :run-at}
   {:label "Created"
    :render-fn :created-at}
   {:label ""
    :render-fn
    (fn [{:keys [id]}]
      [:div {}
       (form-link-button request (url "/job/" id "/run") "Run now")])}])

(defn failed-job-columns [request]
  [{:label "Queue"
    :render-fn :queue}
   {:label "Job name"
    :render-fn
    (fn [{:keys [id job-name]}]
      [:a {:href (url "/failed/" id)} job-name])}
   {:label "Actor"
    :render-fn :actor}
   {:label "Args"
    :render-fn (comp escape-html str :args)}
   {:label "Last picked"
    :render-fn :picked-at}
   {:label "Created"
    :render-fn :created-at}
   {:label ""
    :render-fn
    (fn [{:keys [id]}]
      [:div {}
       (form-link-button request (url "/failed/" id "/retry") "Retry")
       (form-link-button request (url "/failed/" id "/delete") "Delete")])}])

(defn recurring-job-columns [request]
  [{:label "Queue"
    :render-fn :queue}
   {:label "Job name"
    :render-fn
    (fn [{:keys [id job-name]}]
      [:a {:href (url "/job/" id)} job-name])}
   {:label "Interval"
    :render-fn :interval}
   {:label "Actor"
    :render-fn :actor}
   {:label "Args"
    :render-fn (comp escape-html str :args)}
   {:label "Retries"
    :render-fn :attempt}
   {:label "Last picked"
    :render-fn :picked-at}
   {:label "Scheduled for"
    :render-fn :run-at}
   {:label "Created"
    :render-fn :created-at}
   {:label ""
    :render-fn
    (fn [{:keys [id]}]
      [:div {}
       (form-link-button request (url "/job/" id "/run") "Run now")])}])

(defn job-table [jobs col-defs]
  [:table.table.table-striped.table-hover {}
   [:thead {}
    [:tr {}
     (for [{:keys [label]} col-defs]
       [:th {} label])]]
   [:tbody {}
    (for [j jobs]
      [:tr {}
       (for [{:keys [render-fn]} col-defs]
         [:td {} (render-fn j)])])]])

(defn job-list-scripts []
  (list
    [:script {:src (url "/jquery-3.3.1.min.js")}]
    [:script {:src (url "/selectize.min.js")}]
    [:script {}
     "$(function () {
        $('#queue-pick')
          .selectize({})
          .change(function() {
            let query = 'queue=' + this.value;
            if (this.value !== '::none')
              window.location.search = encodeURI(query);
            else
              window.location.search = '';
          });
      });"]))

(defn flash-toast [flash]
  [:div.toast {:style "margin-bottom: 12px;"} flash])

(defn job-list [{:keys [request filt flash queue queue-names jobs stats]}]
  (html
    [:html {}
     (head (case filt
             :queued "Queued jobs"
             :scheduled "Scheduled jobs"
             "All jobs"))
     [:body {}
      (navbar (case filt
                :queued :queued-jobs
                :scheduled :scheduled-jobs
                :all-jobs))
      [:section.container {:style "padding: 0px 24px 12px;"}
        (when flash
          (flash-toast flash))
        [:section {}
         (breadcrumbs
           [(case filt
              :queued {:title "Queued jobs", :url (url "/queued")}
              :scheduled {:title "Scheduled jobs", :url (url "/scheduled")}
              {:title "All jobs", :url (url "/all")})
            {:title (if queue (str "queue:" queue) "All queues")
             :url (url
                    (case filt
                      :queued "/queued"
                      :scheduled "/scheduled"
                      "/all")
                    (when queue {:queue queue}))}]
           (queue-select queue-names queue))]
        (stats-grid stats)
        (job-table jobs (job-columns request))
        (job-list-scripts)]]]))

(defn failed-job-list [{:keys [request flash queue queue-names jobs stats]}]
  (html
    [:html {}
     (head "Failed jobs")
     [:body {}
      (navbar :failed-jobs)
      [:section.container {:style "padding: 0px 24px 12px;"}
        (when flash
          (flash-toast flash))
        [:section {}
         (breadcrumbs
           [{:title "Failed jobs", :url (url "/failed")}
            {:title (if queue (str "queue:" queue) "All queues")
             :url (url
                    "/failed"
                    (when queue {:queue queue}))}]
           (queue-select queue-names queue))]
        (stats-grid stats)
        (job-table jobs (failed-job-columns request))
        (job-list-scripts)]]]))

(defn recurring-job-list [{:keys [request flash queue queue-names jobs stats]}]
  (html
    [:html {}
     (head "Recurring jobs")
     [:body {}
      (navbar :recurring-jobs)
      [:section.container {:style "padding: 0px 24px 12px;"}
        (when flash
          (flash-toast flash))
        [:section {}
         (breadcrumbs
           [{:title "Recurring jobs", :url (url "/recurring")}
            {:title (if queue (str "queue:" queue) "All queues")
             :url (url
                    "/recurring"
                    (when queue {:queue queue}))}]
           (queue-select queue-names queue))]
        (stats-grid stats)
        (job-table jobs (recurring-job-columns request))
        (job-list-scripts)]]]))

(def single-job-fields
  [{:label "Job name"
    :render-fn :job-name}
   {:label "Queue"
    :render-fn
    (fn [{:keys [queue]}]
      [:a {:href (url "/all" {:queue queue})} queue])}
   {:label "Actor"
    :render-fn :actor}
   {:label "Args"
    :render-fn (comp escape-html str :args)}
   {:label "Interval"
    :cond-fn :interval
    :render-fn :interval}
   {:label "Status"
    :render-fn :status}
   {:label "Last picked"
    :render-fn :picked-at}
   {:label "Retries"
    :render-fn :attempt}
   {:label "Run at"
    :render-fn :run-at}
   {:label "Created at"
    :render-fn :created-at}
   {:label "Last error"
    :cond-fn :last-error
    :render-fn
    (fn [{:keys [last-error]}]
      [:pre {} (escape-html last-error)])}])

(def single-failed-job-fields
  [{:label "Job name"
    :render-fn
    (fn [{:keys [job-name related-job-id]}]
      (if related-job-id
        [:a {:href (url "/job/" related-job-id)} job-name]
        job-name))}
   {:label "Queue"
    :render-fn
    (fn [{:keys [queue]}]
      [:a {:href (url "/all" {:queue queue})} queue])}
   {:label "Actor"
    :render-fn :actor}
   {:label "Args"
    :render-fn (comp escape-html str :args)}
   {:label "Retries"
    :render-fn :attempt}
   {:label "Last picked at"
    :render-fn :picked-at}
   {:label "Created at"
    :render-fn :created-at}
   {:label "Last error"
    :cond-fn :last-error
    :render-fn
    (fn [{:keys [last-error]}]
      [:pre {} (escape-html last-error)])}])

(defn single-job [{:keys [flash job]}]
  (let [{:keys [id job-name queue]} job]
    (html
      [:html {}
       (head (str "Job " job-name))
       [:body {}
        (navbar :all-jobs)
        [:section.container {:style "padding: 0px 24px 12px;"}
          (when flash
            (flash-toast flash))
          [:section {}
           (breadcrumbs
             [{:title "All jobs", :url (url "/all")}
              {:title (str "queue:" queue)
               :url (url "/all" {:queue queue})}
              {:title job-name
               :url (url "/job/" id)}])]
          [:section {}
           (for [{:keys [label cond-fn render-fn]
                  :or {cond-fn (constantly true)}} single-job-fields]
             (when (cond-fn job)
               [:div.tile.tile-centered {}
                [:div.tile-content {}
                 [:div.tile-title.text-bold {} label]
                 [:div.tile-subtitle {} (render-fn job)]]]))]]]])))

(defn single-failed-job [{:keys [flash job]}]
  (let [{:keys [id job-name queue]} job]
    (html
      [:html {}
       (head (str "Failed job " job-name))
       [:body {}
        (navbar :failed-jobs)
        [:section.container {:style "padding: 0px 24px 12px;"}
          (when flash
            (flash-toast flash))
          [:section {}
           (breadcrumbs
             [{:title "Failed jobs", :url (url "/failed")}
              {:title (str "queue:" queue)
               :url (url "/failed" {:queue queue})}
              {:title job-name
               :url (url "/failed/" id)}])]
          [:section {}
           (for [{:keys [label cond-fn render-fn]
                  :or {cond-fn (constantly true)}} single-failed-job-fields]
             (when (cond-fn job)
               [:div.tile.tile-centered {}
                [:div.tile-content {}
                 [:div.tile-title.text-bold {} label]
                 [:div.tile-subtitle {} (render-fn job)]]]))]]]])))
