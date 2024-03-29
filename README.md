# cljobq

A simple Clojure PostgreSQL-based job queue. An optional web UI is also available as both a library and a [standalone service][cljobq-web-server].

![](https://clojars.org/cljobq/latest-version.svg)

## Usage

See the [API documentation][API] for more detailed usage information, including examples.

```clojure
(defn *-and-log [a b]
  (println "a * b =" (* a b))
  (Thread/sleep 20000))

(require 'cljobq.core)

(cljobq.core/set-context!
  {:db
   {:connection-uri "jdbc:postgresql://localhost:5432/jobq?user=jobq&password=jobq"}})

(cljobq.core/db-migrate!)

(cljobq.core/start
  {:queues
   {:queue-a {}
    :queue-b {:num-threads 4}}})

(cljobq.core/enqueue
  {:actor #'*-and-log
   :args [9 9]
   :queue "queue-b"})

(cljobq.core/enqueue
  {:actor #'*-and-log
   :args [9 7]
   :job-name "recurring-math"
   :queue "queue-c"
   :interval "15 23 * * *"})
```

### Web UI

See the [API documentation][API-web] for more detailed usage information, including examples.

```clojure
(require 'cljobq.web)

;; Create the cljobq-web handler
(def cljobq-handler
  (cljobq.web/make-handler
    {:db {:connection-uri \"jdbc:postgresql://localhost:5432/jobqtest?user=test&password=test\"}))

;; Start an HTTP server with the handler (e.g. with http-kit)
(require 'org.httpkit.server)
(org.httpkit.server/run-server cljobq-handler)
```


## License

Copyright © 2018 Alex Hornung

Distributed under the Eclipse Public License either version 1.0 or (at
your option) any later version.


[API]: https://bwalex.github.io/cljobq/cljobq.core.html
[API-web]: https://bwalex.github.io/cljobq/cljobq.web.html
[cljobq-web-server]: https://github.com/bwalex/cljobq-web-server
