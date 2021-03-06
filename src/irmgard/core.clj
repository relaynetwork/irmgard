(ns irmgard.core
  (:require
   [clojure.data.json     :as json]
   [clojure.java.jdbc     :as jdbc]
   [clojure.tools.logging :as log])
  (:import
   [java.sql Connection         DriverManager]
   [java.util.concurrent.atomic AtomicBoolean]))


;; See:   "http://jdbc.postgresql.org/documentation/91/listennotify.html"

(defonce registry         (atom {}))
(defonce indexed-registry (atom {}))

(defn clear-registry []
  (reset! registry         {})
  (reset! indexed-registry {}))

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
   :host        (or (c :host) "localhost")
   :port        (c :port)})

(defn obtain-mutex [conf conn]
  (try
   (jdbc/do-commands "LOCK TABLE irmgard.process_log IN EXCLUSIVE MODE NOWAIT")
   ;; got the lock
   true
   (catch java.sql.SQLException ex
     ;; did not get the lock
     false)))

(defn group-row-updates [recs]
  (reduce (fn [acc r]
            (update-in acc [[(:schema_name r)
                             (:table_name  r)]]
                       conj r))
          {} recs))

(defn process-row-changes [conf conn]
  ;; obtain mutex, nowait
  ;; TODO: handle failure to get lock (probably an exception)
  (let [dbname        (or
                       (-> conf :conf :database)
                       (-> conf :conf :subname))]
    (jdbc/transaction
     (if (obtain-mutex conf conn)
       (do
         (log/infof "obtained EXCLUSIVE lock on irmgard.process_log")
         ;; select and process the first block of records
         ;; TODO: make the LIMIT configurable
         (loop [recs (exec-sql "SELECT * FROM irmgard.row_changes ORDER BY event_id LIMIT 100" [])]
           (when (not (empty? recs))
             ;; TODO: Instead of NxM, we could group the list of records by schema_name.table_name and pass them
             ;; to the observer-fn as a seq
             (doseq [[[schema-name table-name] recs] (group-row-updates recs)]
               (doseq [[observer-name observer-fn] (find-listeners dbname schema-name table-name)]
                 (observer-fn recs))
               (doseq [rec recs]
                 (jdbc/do-prepared "DELETE FROM irmgard.row_changes WHERE event_id=?" [(:event_id rec)])))
             (recur (exec-sql "SELECT * FROM irmgard.row_changes ORDER BY event_id LIMIT 100" [])))))
       (log/infof "did not get lock on irmgard.process_log, another process is working with the table."))
     :ok)))

;; get lock, nowait
;;   determine block SIZE=N
;;   min id, max id in the block
;;   group by table/schema
;;   dispatch to all registerd listeners


(defn dispatch-notifications [conf conn force-check-table?]
  ;; this select is purely for side-effect
  (exec-sql "SELECT 1" [])
  ;; NB: also, just check the table to see if it contains anything
  (let [dbname        (or (-> conf :conf :database)
                          (-> conf :conf :subname))
        notifications (.getNotifications conn)]
    (when (or (not (nil? notifications))
              (and force-check-table?
                   (not (empty? (exec-sql "SELECT * FROM irmgard.row_changes LIMIT 1" [])))))
      (log/warnf "dispatch-notifications[%s]: %s notifications" dbname (count notifications))
      (process-row-changes conf conn))))

(defn config->jdbc-url [c]
  (format "jdbc:postgresql://%s:%s@%s:%s/%s"
          (c :user)
          (c :password)
          (c :host     "localhost")
          (c :port     "5432")
          (or (c :database)
              (c :subname))))

(comment

  (jdbc/with-connection {:connection-uri (config->jdbc-url (:conf conf))
                         :username       (:user (:conf conf))
                         :password       (:password (:conf conf))}
    (jdbc/find-connection))


  (jdbc/with-connection {:port 5432
                         :host "127.0.0.1"
                         :password "postgres1"
                         :user "rails"
                         :classname "org.postgresql.Driver"
                         ;; :subname "relayzone_development"
                         :subname (str "//127.0.0.1:5432/relayzone_development" )
                         :subprotocol "postgresql"
                         :sleep-time 2500}
    (jdbc/find-connection))

)


(defn db-watcher [conf]
  ;; TODO: implement try/catch/finally to clean up:
  ;;    UNLISTEN irmgard when terminating
  ;;    close database connection, ensure we do that in a finally block
  (jdbc/with-connection (:dbconf conf)
    (jdbc/do-commands "LISTEN irmgard")
    (let [conn                (jdbc/find-connection)
          dbname              (-> conf :dbconf :subname)
          sleep-time          (:sleep-time conf 1000)
          table-check-every-n (:table-check-every-n conf 10)]
      (loop [conf     conf
             continue (.get (:continue conf))
             times    0]
        (if continue
          (do
            (log/infof "db-watcher[%s] polling (delay=%s)" dbname sleep-time)
            (try
             (dispatch-notifications conf conn (zero? (mod times table-check-every-n)))
             (catch Exception e
               (log/errorf e "Error dispatching notifications.  Some updates failed to propigate! Retry will occurr on next NOTIFY.  Error: %s" e)))
            (try
             (Thread/sleep sleep-time)
             (catch Exception e
               ;; do nothing. sleep interrupted by notify
               ))
            (recur conf
                   (.get (:continue conf))
                   (inc times)))
          (do
            (log/infof "db-watcher[%s] terminating" dbname)))))))


(defn start-watcher
  "Start an Irmgard watcher for a database.

  wname is the watcher name, a Clojure keyword.
  conf  is a map that will be passed to clojure.java.jdbc/with-connection
"
  [wname conf]
  (let [control-atom   (AtomicBoolean. true)
        watcher-config {:conf     conf
                        :error    (atom nil)
                        :continue control-atom
                        :dbconf   conf
                        :sleep-time (:sleep-time conf)}
        watcher-thread (Thread. (fn [] (db-watcher watcher-config)))]
    (swap! db-watchers assoc wname (assoc watcher-config :thread watcher-thread))
    (.start watcher-thread)))

(defn stop-watcher [name]
  (let [conf (get @db-watchers name)]
    (when conf
      (.lazySet (:continue conf) false)
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
  (register-listener :test-listener (:database (config :db)) "irmgard" "example_table"
                     (fn [recs]
                       (log/infof "ROW CHANGE! %s" recs)))

  (do
    (stop-watcher :test1)
    (start-watcher :test1 (db-conn-info (config :db))))


  (jdbc/with-connection  (db-conn-info (config :db))
    (exec-sql "SELECT now()" []))


  (jdbc/with-connection  (db-conn-info (config :db))
    (process-row-changes
     {:dbconf (db-conn-info (config :db))}
     (jdbc/find-connection)))




  )