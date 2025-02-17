(ns telco-billing.core
  (:gen-class)
  (:require
   [telco-billing.input :as in]
   [telco-billing.template :as template]
   [telco-billing.billing :as billing]
   [clojure.spec.alpha :as s]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cli-matic.core :refer [run-cmd]]))

;;; Load database and configuration files
(defn load-edn-file
  "Given a `filename` load and return a file"
  [filename]
  (edn/read-string (slurp filename)))

(defn evaluate-range
  "Given a map with `connection-points` evaluate clojure forms which describes a ranges."
  [{:keys [connection-points]}]
  (flatten (eval connection-points)))

(defn eval-customers
  "Evaluate and update customer connection points which may contain clojure forms."
  [{:keys [customers]}]
  (map #(assoc % :connection-points (evaluate-range %)) customers))

(defn enhance-connection-point
  "Apply the basic fees and name to the connection points unless
  defined otherwise."
  [points dfees dname]
  (map (fn [p] (if (map? p)
                p
                {:num p :name dname :fees dfees})) points))

(defn update-customer-database
  "Evaluate and update the customer database.
  This evaluates open clojure forms which define possible connection points."
  [{:keys [customers basic-fees default-name] :as db}]
  (assoc db :customers
   (->> customers
      (map #(assoc % :connection-points (evaluate-range %)))
      (map #(let [basic (or (:basic-fees %) basic-fees)]
              (update % :connection-points
                      enhance-connection-point basic default-name))))))

(defn load-configuration
  "Load configuration and customer database.
  Prefer customer database defined in configuration file if present."
  [config db]
  (let [config (load-edn-file config)]
   (merge
    config
    (load-edn-file (or (:db-file config) db)))))

(defn parse-input [file]
  (if (str/ends-with? (.toUpperCase (.getName file)) ".DBF")
    (in/parse-dbase-file file)
    (in/parse-csv-file file)))

(defn load-input-files [files]
  (flatten (conj (map parse-input files))))

;;; main functions
(defn run [{:keys [files output db config format]}]
  (when (seq files)
    (println "No input files provided")
    (System/exit 1))

  (let [db (update-customer-database (load-configuration config db))
        template-files (template/read-template-files (:template db))
        in (load-input-files files)
        bills (billing/generate-bills db in)]
    (condp = (format)
      "latex" (doall (template/create-bills template-files output bills))
      "csv" (doall (template/bills->csv output bills))
      :otherwise (println "No matching generation Method found."))))

(s/def ::file #(.isFile (io/file %)))
(s/def ::directory #(.isDirectory (io/file %)))

(def ARGS
  {:command "telco"
   :description "A telco billing automation tool."
   :version "0.1.3"
   :opts [{:option "output"
           :short "o"
           :as "Path to output directory"
           :type :string
           :default "out"
           :spec ::directory}
          {:option "db"
           :short "d"
           :as "Path to the customer database"
           :type :string
           :default "resources/customer.db"
           :spec ::file}
          {:option "config"
           :short "c"
           :as "Path to the configuration file"
           :type :string
           :default "resources/conf.cnf"
           :spec ::file}
          {:option "format"
           :short "f"
           :as "Which format is used as output. At the moment csv and latex are supported."
           :type #{"csv" "latex"}
           :default "latex"}
          {:option "files"
           :short 0
           :as "Input files"
           :type :string
           :multiple true}]
   :runs run})

(defn -main
  "Simply start the telco billing automation and parse the arguments."
  [& args]
  (run-cmd args ARGS))

(defn customers-map->csv
  "Utility function to convert a list of` customers` into a csv like format."
  [customers]
  (flatten (loop [in customers
                  out []]
             (if (empty? in)
               out
               (let [c (first in)
                     n (:name c)
                     f #(str n "," (:num %) "," (:fees %))]
                 (recur (rest in)
                        (conj out (map f (:connection-points c)))))))))

(defn count-connection-points-by-fee [customers fees]
  (reduce (fn [acc c]
            (+ acc
               (reduce
                #(if (= fees (:fees %2)) (+ %1 1) 0) 0 (:connection-points c)))) 0 customers))

(defn get-overall-fees [bills]
  (reduce (fn [acc bill]
            (+ acc (:billing-fees bill))) 0 bills))
