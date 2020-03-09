(ns telco-billing.billing
  (:require [clojure.string :as str]))

(defn in? [elem coll]
  (some #(= elem %) coll))

(defn valid-point?
  "Check if some connection `point` and the `record` match."
  [point record]
  (str/ends-with? (:con-point record) (str point)))

(defn match-con-with-records
  "Assign all recorded calls to some `con` point.

  Creates a new map with the connection point and if the associated calls."
  [con records]
  (let [point (:num con)
        calls (filter #(valid-point? point %) records)]
    (if (seq calls)
      {:point con
       :calls calls}
      {:point con
       :calls []})))

(defn assign-calls-to-connection-points
  "Assign to a list of connection points the associated calls. "
  [cons records]
  (loop [in cons
         out []]
    (if (empty? in)
      out
      (recur (rest in)
             (conj out (match-con-with-records (first in) records))))))

(defn parse-call-zone
  "Parse the call zone either to an int or leave it as string."
  [zone]
  (if (Character/isDigit (first zone))
    (Integer/parseInt (str zone))
    (str zone)))

(defn apply-fees [fees zone-name duration con-only?]
  (if con-only?
    {:zone-fees fees :zone-name zone-name :fees fees}
    {:zone-fees fees :zone-name zone-name :fees (* (/ fees 60) duration)}))

(defn calculate-tzone-fees
  "Calculate the fees of a call.

  Since the Call zones are Numbers with leading zeros or
  string some kind of parsing is needed."
  [tzones default-fees call]
  (let [call-zone (parse-call-zone (:tzone call))
        fee-zone (first (filter #(in? call-zone (:zone %)) tzones))
        con-only (:con-only fee-zone)
        duration (if (instance? java.lang.String (:duration call))
                   (Integer/parseInt (:duration call))
                   (:duration call))]
    (conj call
          (if (map? fee-zone)
             (apply-fees (:fees fee-zone) (:name fee-zone) duration con-only)
             (apply-fees default-fees "Default Zone" duration con-only)))))

(defn apply-tzones-on-calls
  "Apply the tzones to the calls of a connection point."
  [tzones default-fees calls]
  (map (partial calculate-tzone-fees tzones default-fees) calls))

(defn calculate-connection-point-fees
  [con-point]
  (let [fees (reduce #(+ %1 (:fees %2)) 0 (:calls con-point))]
    (conj con-point
          {:fees fees})))

(defn apply-tzones-on-con-points
  "Apply the defined tzones to the connection points."
  [con-points tzones default-fees]
  (let [f (partial apply-tzones-on-calls tzones default-fees)]
    (map #(calculate-connection-point-fees (assoc % :calls (f (:calls %)))) con-points)))

(defn generate-bill-header
  "Get the header informations used later in the templating to generate a bill."
  [bill-number customer con-points basic-fees date]
  (let [fees (reduce #(+ %1 (:fees %2)) 0 con-points)
        basic-fees (* basic-fees (count con-points))]
    {:billing-date date
     :billing-number bill-number
     :billing-name (str (:name customer) "_" bill-number)
     :billing-fees (+ basic-fees fees)
     :customer (dissoc customer :connection-points)}))

(defn generate-bill [customer input bill-number db]
  (let [{:keys [tzones default-fees basic-fees bill-date]} db
        customer-fee (:basic-fees customer)
        con-points (assign-calls-to-connection-points
                    (:connection-points customer) input)
        con-points (apply-tzones-on-con-points con-points tzones default-fees)
        bill-header (generate-bill-header bill-number customer
                                          con-points
                                          (if customer-fee
                                            customer-fee
                                            basic-fees)
                                          bill-date)]
    (conj bill-header {:connection-points con-points})))

;; telco calculation
(defn generate-bills [db input]
  (when input
    (let [{:keys [customers bill-number bill-number-prefix]} db]
      (loop [in customers
             bill-number (Integer/parseInt bill-number)
             out []]
        (if (empty? in)
          out
          (let [bill-num (str bill-number-prefix bill-number)
                bill (generate-bill (first in) input bill-num db)]
          (recur (rest in) (inc bill-number)
                 (conj out (conj bill {:billing-pos (:cost-position db)})))))))))
