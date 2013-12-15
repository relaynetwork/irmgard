(ns irmgard.test-core
  (:use
   clojure.test)
  (:require
   [irmgard.core :as irmgard]))


(deftest test-registry
  (irmgard/register-listener :test-listener :testdb :irmgard :example_table
                             (fn [rec]
                               true))
  (is (= 1 (count (irmgard/find-listeners :testdb :irmgard :example_table))))
  (irmgard/unregister-listener :test-listener)
  (is (= 0 (count (irmgard/find-listeners :testdb :irmgard :example_table)))))
