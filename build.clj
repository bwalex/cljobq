(ns build
  (:require
    [clojure.string :as str]
    [clojure.tools.build.api :as b]
    [clojure.tools.deps :as t]))

(def version (format "0.2.%s" (b/git-count-revs nil)))

(def lib 'cljobq/cljobq)
(def class-dir "target/classes")
(def basis (b/create-basis {:project "deps.edn"}))
(def basis-uber (b/create-basis {:project "deps.edn"
                                 :aliases [:web/standalone]}))
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def uber-file (format "target/clojbq-web-standalone-%s.jar" version))

(defn- run-task [aliases]
  (println "\nRunning task for" (str/join "," (map name aliases)))
  (let [basis    (b/create-basis {:aliases aliases})
        combined (t/combine-aliases basis aliases)
        cmds     (b/java-command
                  {:basis basis
                   :java-opts (:jvm-opts combined)
                   :main      'clojure.main
                   :main-args (:main-opts combined)})
        {:keys [exit]} (b/process cmds)]
    (when-not (zero? exit) (throw (ex-info "Task failed" {})))))

(defn clean [_]
  (b/delete {:path "target"}))

(defn docs [_]
  (b/delete {:path "target/doc"})
  (run-task [:codox]))

(defn jar [_]
  (b/delete {:path class-dir})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis
                :src-dirs ["src/main"]})
  (b/copy-dir {:src-dirs ["src/main" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file jar-file}))

(defn standalone [_]
  (b/delete {:path class-dir})
  (run-task [:web/standalone :kondo])
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis basis-uber
                :src-dirs ["src/main" "src/standalone"]})
  (b/copy-dir {:src-dirs ["src/main" "src/standalone" "resources"]
               :target-dir class-dir})

  (b/compile-clj {:basis basis-uber
                  :src-dirs ["src/main" "src/standalone"]
                  :class-dir class-dir})

  (b/uber {:class-dir class-dir
           :uber-file uber-file
           :main 'cljobq.web.standalone
           :basis basis-uber}))
