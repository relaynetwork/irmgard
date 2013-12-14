(ns irmgard.core
  (:require
   [clojure.data.json :as json])
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

(defn config->jdbc-url [c]
  (format "jdbc:postgresql://%s:%s/%s"
          (c :host     "localhost")
          (c :port     "5432")
          (c :database)))

(defn connect-to-db [c]
  (let [url (config->jdbc-url c)]
    (Class/forName "org.postgresql.Driver")
    (DriverManager/getConnection url (:username c) (:password c))))

(comment

  "http://jdbc.postgresql.org/documentation/91/listennotify.html"

  (with-open [conn (connect-to-db (config :db))]
    )




  )