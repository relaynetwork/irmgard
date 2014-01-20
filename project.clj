(defproject com.relaynetwork/irmgard "0.1.4"
  :description "Irmgard: Postgres Event and Replication Framework for PostgreSQL"
  :repositories [["releases"  {:url "s3p://relay-maven-repo/releases/"  :creds :gpg}]
                 ["snapshots" {:url "s3p://relay-maven-repo/snapshots/" :creds :gpg}]
                 ["sonatype"  {:url "https://oss.sonatype.org/content/groups/public/"}]]
  :url         "https://github.com/rn-superg/irmgard"
  :license     {:name "Eclipse Public License"
                :url "http://www.eclipse.org/legal/epl-v10.html"}
  :lein-release {
    :scm :git
  }
  :plugins      [[s3-wagon-private          "1.1.1"]
                 [lein-release/lein-release "1.0.5"]]
  :resource-paths ["resources"]
  :profiles {:dev {:resource-paths ["dev-resources"]}}
  :dependencies [
    [org.clojure/clojure       "1.5.1"]
    [org.clojure/data.json     "0.2.2"]
    [org.postgresql/postgresql "9.3-1100-jdbc41"]
    [org.clojure/java.jdbc     "0.2.3"]
    [org.clojure/tools.logging "0.2.6"]
    [ch.qos.logback/logback-classic "1.0.13"]
  ])
