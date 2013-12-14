(ns irmgard.core
  (:require
   [clojure.data.json :as json]
   [clojure.java.jdbc :as jdbc])
  (:import
   [java.sql Connection DriverManager]))

(defn foo
  "I don't do a whole lot."
  [x]
  (println x "Hello, World!"))

(def config-state (atom {}))

(defn config-contents []
  (cond
    (nil? @config-state)
    (do
      (reset! config-state
              {:mtime  (.lastModified (java.io.File. "conf.json"))
               :config (json/read-str (slurp "conf.json") :key-fn keyword)})
      (:config @config-state))

    (< (:mtime @config-state 0)
       (.lastModified (java.io.File. "conf.json")))
    (do
      (reset! config-state
              {:mtime  (.lastModified (java.io.File. "conf.json"))
               :config (json/read-str (slurp "conf.json") :key-fn keyword)})
      (:config @config-state))

    :cache-is-up-to-date
    (:config @config-state)))

;; (config-contents)

(defn config [& path]
  (loop [c          (config-contents)
         [p & path] path]
    (cond
      (and (empty? path)
           (not p))
      c

      (empty? path)
      (get c p)

      :recur
      (recur (get c p)
             path))))

(defn db-conn-info [c]
  {:subprotocol "postgresql"
   :subname     (c :database)
   :classname   "org.postgresql.Driver"
   :user        (c :username)
   :password    (c :password)
   :port        (c :port)})

(defn config->jdbc-url [c]
  (format "jdbc:postgresql://%s:%s/%s"
          (c :host     "localhost")
          (c :port     "5432")
          (c :database)))

(defn connect-to-db [c]
  (let [url (config->jdbc-url c)]
    (Class/forName "org.postgresql.Driver")
    (DriverManager/getConnection url (:username c) (:password c))))

(defn exec-sql [stmt binds & opts]
  (jdbc/with-query-results rs (vec (cons stmt binds))
    (vec rs)))

(comment
  #_(with-open [conn (connect-to-db (config :db))]
      (jdbc/with-connection {:conn conn}
        (exec-sql "SELECT now()" [])))

  (jdbc/with-connection  (db-conn-info (config :db))
    (exec-sql "SELECT now()" []))

  "http://jdbc.postgresql.org/documentation/91/listennotify.html"


  (def keep-running (atom true))
  (reset! keep-running false)

  (def nlist (atom []))
  (class @nlist)
  (first @nlist)

  (map (fn [n]
         {:name      (.getName n)
          :parameter (.getParameter n)}) @nlist)

  ;; NB: needs to be in a background thread...
  (jdbc/with-connection (db-conn-info (config :db))
    (jdbc/do-commands "LISTEN irmgard")
    (loop [continue @keep-running]
      ;;;
      (exec-sql "SELECT 1" [])
      (let [notifications (.getNotifications (jdbc/find-connection))]
        (println (format "Found %s notifications" (count notifications)))
        (swap! nlist (fn [curr & args]
                       (vec (apply concat curr args)))
               notifications))
      (if continue
        (do
          (Thread/sleep 1000)
         (recur @keep-running))
        (println "terminating")))
    ;; NB: this has to be in a finally block or it pollutes the connection
    ;; instead, irmgard clients should not use a connection pool(!)
    (jdbc/do-commands "UNLISTEN irmgard"))


  )