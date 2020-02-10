(defproject telco-billing "0.1.0"
  :description "Automated bill generation for versatel and plusnet itemized bills."
  :url "https://github.com/santifa/telco-billing"
  :license {:name "EPL-2.0 OR GPL-2.0-or-later WITH Classpath-exception-2.0"
            :url "https://www.eclipse.org/legal/epl-2.0/"}
  :dependencies [[org.clojure/clojure "1.10.0"]
                 [org.clojure/tools.cli "0.4.2"]
                 [org.clojure/data.csv "0.1.4"]
                 [clj-dbase "0.1.0"]
                 [org.clojars.hozumi/clj-commons-exec "1.2.0"]]
  :main ^:skip-aot telco-billing.core
  :target-path "target/%s"
  :profiles {:uberjar {:aot :all}})
