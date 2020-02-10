(ns telco-billing.core
  (:gen-class)
  (:require
   [telco-billing.input :as in]
   [telco-billing.template :as template]
   [telco-billing.billing :as billing]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.tools.cli :refer [parse-opts]]
   [clojure.string :as str]))

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

(defn enhance-connection-points
  "Add the default name and basic fees to connection points."
  [customers basic-fees default-name]
  (map #(update % :connection-points
                enhance-connection-point basic-fees default-name)) customers)

(defn load-db-file
  "Load and intially evalute the database.

  Provide a map with a path to the `db-file`, the `basic-fees`
  for a connection point and its `default-name`"
  [{:keys [db-file basic-fees default-name]}]
  (when-let [db (load-edn-file db-file)]
    {:customers
     (map #(update % :connection-points
                   enhance-connection-point basic-fees default-name)
          (eval-customers db))}))

(defn startup
  "Merge the different config and database files into one.
  The priority is first the config file second the database file
  and third the cli input."
  [options]
  (let [config (load-edn-file (:config options))
        config (if (:db-file config)
                 config
                 (assoc config :db-file (:db options)))]
    (merge config (load-db-file config))))

(defn parse-input [file]
  (if (str/ends-with? (.toUpperCase (.getName file)) ".DBF")
    (in/parse-dbase-file file)
    (in/parse-csv-file file)))

(defn load-input-files [files]
  (flatten (conj (map parse-input files))))

;;; main functions
(defn run [files options]
  (let [db (startup options)
        template-files (template/read-template-files (:template db))
        in (load-input-files files)
        bills (billing/generate-bills db in)]
    (doall (template/create-bills template-files (:output options) bills))))

;; cli handling
(def cli-options
  [["-h" "--help"]
   ["-o" "--output PATH" "Path to output directory"
    :default "out"
    :parse-fn #(io/file %)
    :validate [#(.isDirectory %) "Must be a file denoting a folder."]]
   ["-d" "--db PATH" "Path to the customer database."
    :default "resources/customer.db"
    :parse-fn #(io/file %)
    :validate [#(.isFile %) "Must be a file denoting the database."]]
   ["-c" "--config PATH" "Path to the configuration file."
    :default "resources/conf.cnf"
    :parse-fn #(io/file %)
    :validate [#(.isFile %) "Must be a file denoting the config file."]]])

(defn usage [option-summary]
  (->> ["This is the telco billing automation."
        ""
        ""
        "Usage: telco [options] <dbase input>"
        ""
        "Options:"
        option-summary
        ""
        "The defaults are pointing to the test development enviroment."]))

(defn error-msg [errors]
  (str "The following errors occured by parsing the cli arguments:\n\n"
       (clojure.string/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary]} (parse-opts args cli-options)]
    (cond
      (:help options)
      {:exit-message (usage summary) :ok? true}
      errors
      {:exit-message (error-msg errors)}
      (and (pos? (count arguments))
           (.isFile (io/file (first arguments))))
      {:inputs (map io/file arguments) :options options}
      :else
      {:exit-message (usage summary)})))

(defn exit [status msg]
  (println msg)
  (System/exit status))

(defn exit-test [status msg]
  (println status ":" msg))

(defn -main
  "I don't do a whole lot ... yet."
  [& args]
  (let [{:keys [inputs options exit-message ok?]} (validate-args args)]
    (if exit-message
      (exit-test (if ok? 0 1) exit-message)
      (run inputs options))))
