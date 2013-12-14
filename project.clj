(defproject com.relaynetwork/irmgard "0.1.0-SNAPSHOT"
  :description "Irmgard: Postgres Event and Replication Framework for PostgreSQL"
  :url         "https://github.com/rn-superg/irmgard"
  :license     {:name "Eclipse Public License"
                :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [
    [org.clojure/clojure "1.5.1"]
    [org.clojure/data.json "0.2.2"]
    [org.postgresql/postgresql "9.3-1100-jdbc41"]
    [org.clojure/java.jdbc "0.2.3"]
  ])
