(ns irmgard.core
  (:require
   [clojure.data.json     :as json]
   [clojure.java.jdbc     :as jdbc]
   [clojure.tools.logging :as log])
  (:import
   [java.sql Connection DriverManager]))


;; See:   "http://jdbc.postgresql.org/documentation/91/listennotify.html"

(defonce registry         (atom {}))
(defonce indexed-registry (atom {}))

(defn clear-registry []
  (reset! registry         {})
  (reset! indexed-registry {}))

;; (clear-registry)

(defn register-listener [listener-name dbname schema-name table-name f]
  (swap! registry assoc listener-name {:dbname dbname
                                       :schema schema-name
                                       :table  table-name
                                       :fn     f})
  (let [path      [(name dbname) (name schema-name) (name table-name)]
        listeners (or (get-in @indexed-registry path)
                      (atom {}))]
    (swap! listeners        assoc listener-name f)
    (swap! indexed-registry assoc-in path listeners)))

(defn unregister-listener [listener-name]
  (let [info        (get @registry listener-name)
        dbname      (:dbname info)
        schema-name (:schema info)
        table-name  (:table  info)
        listeners   (get-in @indexed-registry [(name dbname) (name schema-name) (name table-name)])]
    (swap! registry         dissoc listener-name)
    (swap! listeners dissoc listener-name)))

(defn find-listeners [dbname schema tname]
  (let [listeners (get-in @indexed-registry [(name dbname) (name schema) (name tname)])]
    (when listeners
      @listeners)))

(defonce db-watchers (atom {}))

(defn exec-sql [stmt binds & opts]
  (jdbc/with-query-results rs (vec (cons stmt binds))
    (vec rs)))

(defn db-conn-info [c]
  {:subprotocol "postgresql"
   :subname     (or (c :database)
                    (c :subname))
   :classname   "org.postgresql.Driver"
   :user        (or (c :username) (c :user))
   :password    (c :password)
   :port        (c :port)})

(defn process-row-changes [conf conn]
  ;; obtain mutex, nowait
  (let [dbname        (-> conf :dbconf :subname)]
   (jdbc/transaction
    (jdbc/do-commands "LOCK TABLE irmgard.process_log IN EXCLUSIVE MODE NOWAIT")
    (log/infof "obtained EXCLUSIVE lock on irmgard.process_log")
    ;; select and process the first block of records
    ;; TODO: make the LIMIT configurable
    (loop [recs (exec-sql "SELECT * FROM irmgard.row_changes ORDER BY event_id LIMIT 100" [])]
      (when (not (empty? recs))
        (doseq [rec recs]
          (doseq [[observer-name observer-fn] (find-listeners dbname (:schema_name rec) (:table_name rec))]
            (observer-fn rec))
          (jdbc/do-prepared "DELETE FROM irmgard.row_changes WHERE event_id=?" [(:event_id rec)]))
        (recur (exec-sql "SELECT * FROM irmgard.row_changes ORDER BY event_id LIMIT 100" []))))
    :ok)))

(defn dispatch-notifications [conf conn]
  ;; this select is purely for side-effect
  (exec-sql "SELECT 1" [])
  (let [dbname        (-> conf :dbname :subname)
        notifications (.getNotifications conn)]
    (when (not (nil? notifications))
      (log/infof "dispatch-notifications[%s]: %s notifications" dbname (count notifications))
      (process-row-changes conf conn))))

(defn config->jdbc-url [c]
  (format "jdbc:postgresql://%s:%s/%s"
          (c :host     "localhost")
          (c :port     "5432")
          (c :database)))

(defn db-watcher [conf]
  ;; TODO: implement try/catch/finally to clean up:
  ;;    UNLISTEN irmgard when terminating
  ;;    close database connection, ensure we do that in a finally block
  (jdbc/with-connection (db-conn-info (:dbconf conf))
    (jdbc/do-commands "LISTEN irmgard")
    (let [conn       (jdbc/find-connection)
          dbname     (-> conf :dbconf :subname)
          sleep-time (:sleep-time conf 1000)]
      (loop [conf     conf
             continue @(:continue conf)]
        (if continue
          (do
            (log/infof "db-watcher[%s] polling" dbname)
            (dispatch-notifications conf conn)
            (try
             (Thread/sleep sleep-time)
             (catch Exception e
               ;; do nothing
               ))
            (recur conf @(:continue conf)))
          (do
            (log/infof "db-watcher[%s] terminating" dbname)))))))

(defn start-watcher [wname conf]
  (let [control-atom   (atom true)
        watcher-config {:conf     conf
                        :error    (atom nil)
                        :continue control-atom
                        :dbconf   conf}
        watcher-thread (Thread. (fn [] (db-watcher watcher-config)))]
    (swap! db-watchers assoc wname (assoc watcher-config :thread watcher-thread))
    (.start watcher-thread)))

(defn stop-watcher [name]
  (let [conf (get @db-watchers name)]
    (when conf
      (reset! (:continue conf) false)
      (.interrupt (:thread conf))
      (swap! db-watchers dissoc name))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

(defn connect-to-db [c]
  (let [url (config->jdbc-url c)]
    (Class/forName "org.postgresql.Driver")
    (DriverManager/getConnection url (:username c) (:password c))))

(comment
  (start-watcher :test1 (db-conn-info (config :db)))
  (stop-watcher :test1)


  (jdbc/with-connection  (db-conn-info (config :db))
    (exec-sql "SELECT now()" []))

  (register-listener :test-listener (:database (config :db)) "irmgard" "example_table"
                     (fn [r]
                       (log/infof "ROW CHANGE! %s" r)))

  (jdbc/with-connection  (db-conn-info (config :db))
    (process-row-changes
     {:dbconf (db-conn-info (config :db))}
     (jdbc/find-connection)))


  )