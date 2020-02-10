(ns telco-billing.input
  (:require [clojure.data.csv :as csvd]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-dbase.core :as dbase]))

;;; csv input parsing
(defn read-csv-file [file]
  (with-open [reader (io/reader file)]
    (doall (csvd/read-csv reader :separator \;))))

(defn csv->map [csv-data]
  (map zipmap (->> (first csv-data)
                   ;;(map keyword)
                   repeat)
       (rest csv-data)))

(defn time->duration [time]
  (let [t (str/split time #":")
        hour (* 60 (* 60 (Integer/parseInt (first t))))
        minutes (* 60 (Integer/parseInt (second t)))
        seconds (Integer/parseInt (last t))]
    (+ hour (+ minutes seconds))))

(defn row->internal [record]
  (let [date (.parse (java.text.SimpleDateFormat. "dd.MM.yyyy HH:mm:ss")
                     (get record "Datum und Zeit"))]
    (.setHours date (+ 1 (.getHours date)))
    {:con-point (get record "Rufnummer")
     :tzone (get record "Tarif")
     :duration (time->duration (get record "Dauer"))
     :target-number (get record "Gewählte Nummer")
     :date date}))

(defn convert-csv-input [in]
  (map row->internal in))

(defn parse-csv-file [file]
  (-> file
      (read-csv-file)
      (csv->map)
      (convert-csv-input)))

;;; dbase input parsing
(defn record->internal [record]
  (let [date (str (:DATUM record) " " (:UHRZEIT record) "+0000")]
    {:con-point (:NEBENST record)
     :tzone (:ZONE record)
     :duration (:DAUER record)
     :target-number (:ZIELNR record)
     :date (.parse (java.text.SimpleDateFormat. "yyyyMMdd HHmmssZ") date)}))

(defn convert-dbase-input
  "Enrich the input and convert it to a abstract map.

  The new map contains records which are adjusted to simpler terms for
  easier handling in the later process.
  The resulting record contains the list of points where a point is
  {:con-point <> :date <> :tzone <> :target-number <> :duration <>}"
  [dbase-input]
  (let [converted-records (map record->internal (:records dbase-input))]
    (assoc dbase-input :records converted-records)))

(defn parse-dbase-file [file]
  (:records
   (-> file
       (dbase/parse-dbase-file)
       (dbase/field-definition->records)
       (convert-dbase-input))))
