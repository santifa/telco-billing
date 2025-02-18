(ns telco-billing.template
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [taoensso.timbre :as timbre]
            [clj-commons-exec :as exec]))

(defn- copy-file
  [temp-dir f]
  (let [destination (io/file temp-dir (.getName f))]
    (try
      (io/copy f destination)
      (catch Exception e
        (timbre/error "Failed to setup temporary environment.")
        (timbre/error e)))))

(defn create-temp-env [template-folder]
  (let [template-files (->> template-folder io/file file-seq (drop 1))
        base-dir (io/file (System/getProperty "java.io.tmpdir"))
        base-name (str "billing-" (System/currentTimeMillis) "-" (long (rand 1000000000)))
        temp-dir (io/file (str base-dir "/" base-name))]
    (.mkdir temp-dir)
    (doall (map (partial copy-file temp-dir) template-files))
    temp-dir))

;; templating engine
;; only symbols are replaced, no recursion
;; a symbol is defined by ##_symbol_##
(defn replace-template-symbol
  "Replace a single `symbol` in the `text` with its `replacement` value."
  [text symbol replacement]
  (let [symbol (str "##" symbol "##")]
    (str/replace text symbol (str replacement))))

(defn replace-symbols
  "Take a map of `symbol-pairs` and replace the keywords in the text with
  the given values."
  [symbol-pairs text]
  (loop [in symbol-pairs
         out text]
    (if (empty? in)
      out
      (recur (rest in)
             (let [symbol (name (first (first in)))
                   replace (second (first in))]
              (replace-template-symbol out symbol replace))))))

(defn replace-empty-symbols
  "Replace ##_symbols_## in the `text` which are not replaced before."
  [text]
  (str/replace text #"##.*##" ""))

;; (defn filter-binaries [files]
;;   (let [bin (filter #(str/ends-with? (.getName %) "png") files)]
;;     {:bin bin :plain (seq (set/difference (set files) (set bin)))}))

;; convert template to representation
(defn convert [converter tmp-dir]
  (converter tmp-dir))

(def latex-cmd ["pdflatex" "-interaction=nonstopmode"])

(defn latex-converter [dir]
  (let [options {:dir dir}]
    (timbre/info "Compiling template, first run")
    @(exec/sh (conj latex-cmd "main.tex") options)
    (timbre/info "Compiling template, second run")
    @(exec/sh (conj latex-cmd "main.tex") options)))

;; (defn print-converter [dir]
;;   (map (comp print slurp) (read-template-files dir)))

(def evn-list-template
  "\\hspace*{4mm}
  Anschluss: ##con-point##
  \\begin{longtable}{@{\\extracolsep{\\fill}}XlXlXlXlXr@{}}
  Datum & Zielnummer & Dauer & Preiszone & Kosten\\\\\\hline
  ##calls## \\\\\\hline
  Kosten & & & & ##fees## \\euro
  \\end{longtable}")

(def evn-list-template-empty
  "\\hspace*{4mm}
  Anschluss: ##con-point##\\\\
  \\hspace*{4mm}
  keine abgehenden kostenpflichtigen Verbindungen\\\\\\medskip")

(def evn-base-template
  "\\begin{tabular*}{\\textwidth}{c @{\\extracolsep{\\fill}} c c}
  Einzelverbindungsnachweis & ##name## & ##date##
  \\end{tabular}
  \\begin{flushleft}
  ##evn##
  \\end{flushleft}")

(defn fees->ffees [fees]
  (format "%02.2f" (double fees)))

(defn datetime->formatedtime
  "Formate a `date` and time into a friendly human readable variation."
  [date]
  (str (.format
        (java.text.SimpleDateFormat. "dd.MM.yyyy HH:mm:ss") date)))

(defn seconds->duration
  "Convert `seconds` into a duration of hours, minutes and seconds."
  [seconds]
  (let [hours (quot seconds 3600)
        minutes (quot (rem seconds 3600) 60)
        secs (rem (rem seconds 3600) 60)]
    (format "%02d:%02d:%02d" hours minutes secs)))

(defn create-call-list
  "Format a list of calls into a tex list."
  [calls]
  (map (fn [c] (str (datetime->formatedtime (:date c)) " & "
                   (str (.substring (:target-number c) 0 (- (count (:target-number c)) 3)) "***") " & "
                    (seconds->duration (if (instance? java.lang.String (:duration c))
                                         (Integer/parseInt (:duration c))
                                         (:duration c))) " & "
                    (:zone-name c) " & "
                    (format "%02.4f \\euro" (double (:fees c))) " \\\\")) calls))

(defn create-evn-lists [con-points]
  (map (fn [con-point]
         (let [symbols {:con-point (:num (:point con-point))
                        :con-name (:name (:point con-point))
                        :basic-fees (:fees (:point con-point))
                        :calls (str/join (create-call-list (:calls con-point)))
                        :fees (fees->ffees (:fees con-point))}]
           (if (seq (:calls con-point))
             (replace-symbols symbols evn-list-template)
             (replace-symbols symbols evn-list-template-empty)))) con-points))

(defn create-evn [bill]
  (let [symbols {:date (:billing-date bill)
                 :name (:customer-name bill)
                 :evn (str/join (create-evn-lists (:connection-points bill)))}]
    (replace-symbols symbols evn-base-template)))

(def overview-template
  "Gesamtübersicht
  \\begin{longtable}{@{\\extracolsep{\\fill}}XlXcXrXrXr@{}}
  Anschluss & Grundgebühr & Verbindungen & Dauer & Gesprächskosten\\\\\\hline
  ##con-points## \\\\\\hline
  Kosten (Netto) & ##basic## \\euro & & & ##fees## \\euro\\\\
  Umsatzsteuer 16\\% & & & & ##ust## \\euro \\\\
  Gesamt (Brutto) & & & & ##brutto## \\euro
  \\end{longtable}")

(defn con->overview-line [con-point]
  {:number (:num (:point con-point))
   :basic (:fees (:point con-point))
   :cons (reduce (fn [acc _] (+ acc 1)) 0 (:calls con-point))
   :duration (seconds->duration
              (reduce (fn [acc e] (+ acc (if (instance? java.lang.String (:duration e))
                                          (Integer/parseInt (:duration e))
                                          (:duration e)))) 0 (:calls con-point)))
   :fees (fees->ffees (:fees con-point))})

(defn create-overview [bill]
  (let [points (:connection-points bill)
        conss (map con->overview-line points)
        symbols {:basic (reduce (fn [acc e]
                                  (+ acc (:basic e))) 0 conss)
                 :fees (fees->ffees (:billing-fees bill))
                 :brutto (fees->ffees (* 1.16 (:billing-fees bill)))
                 :ust (fees->ffees (* 0.16 (:billing-fees bill)))
                 :con-points (str
                              (str/join "\\\\"
                                        (map #(clojure.string/trim
                                               (str (str/join " & " (vals %)) "\\euro"))
                                             (map #(assoc % :basic (str (:basic %) "\\euro"))
                                                  conss))))}]
    (replace-symbols symbols overview-template)))

(defn fill-template [files symbols]
  (doseq [f files]
    (when-not (or (str/ends-with? (.getName f) "png") (.isDirectory (io/file f)))
      (spit f (replace-empty-symbols (replace-symbols symbols (slurp f)))))))

(defn prepare-bill
  [temp-dir bill]
  (let* [evn (str/join (create-evn bill))
         overview (str/join (create-overview bill))]
    (spit (io/file temp-dir "evn.tex") evn)
    (spit (io/file temp-dir "overview.tex") overview)))

(defn prepare-template
  [temp-dir bill]
  (let [sym (conj (dissoc bill :connection-points :customer) (:customer bill))
        sym (assoc sym :billing-fees (fees->ffees (* 1.16 (:billing-fees sym))))]
    (fill-template (file-seq temp-dir) sym)))

(defn create-bill
  "Create a new bill in a clean temporary enviroment and return the resulting pdf."
  ([template outdir bill]
   (let [temp-dir (create-temp-env template)
         destination-file (io/file outdir (str (:billing-name bill) ".pdf"))]
     (timbre/info "Created temp environment " temp-dir " for " (:billing-name bill))
     (timbre/debug "Replace symbols in template files")
     (prepare-template temp-dir bill)
     (timbre/debug "Write evn and overview files")
     (prepare-bill temp-dir bill)
     (timbre/debug "Starting pdflatex")
     (convert latex-converter temp-dir)

     (try
       (io/copy (io/file temp-dir "main.pdf") destination-file)
       (timbre/info "Copied " (.getName destination-file)  " to " (.getPath destination-file))
       (catch Exception e
         (timbre/error "Failed to copy resulting pdf")
         (timbre/error e))))))

(defn create-bills [template outdir bills]
  (let [f (partial create-bill template outdir)]
    (map f bills)))

(defn bill->csv [outdir bill]
  (let [name (str outdir "/" (:billing-name bill) ".csv")
        header "Name,Grundgebühr,Anschlusspunkt,Dauer,Zielnummer,Zeit,Zone,Zonen-Gebühren,Gebühren"
        keyseq [:duration :target-number :date :zone-name :zone-fees :fees]
        content (flatten
                 (map (fn [con]
                        (if (seq (:calls con))
                          (map #(str (:name (:customer bill)) "," (get-in con [:point :fees]) ","
                                     (get-in con [:point :num]) ","
                                     (str/join "," (map % keyseq)) "") (:calls con))
                          (str (:name (:customer bill)) "," (get-in con [:point :fees]) ","
                               (get-in con [:point :num]) ",,,,,,")))
                      (:connection-points bill)))]
    (println "Creating " name)
    (spit name (str header "\n" (str/join "\n" content)))))

(defn bills->csv [outdir bills]
  (map (partial bill->csv outdir) bills))
