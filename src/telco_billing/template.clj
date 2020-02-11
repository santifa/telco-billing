(ns telco-billing.template
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clj-commons-exec :as exec]))

;; read template files
(defn read-template-files
  "Read a folder of latex template files."
  [path]
  (into {} (map (fn [f] {(keyword (.getName f)) f}) (drop 1 (file-seq (io/file path))))))

;; temp handling
(defn mk-tmp-dir
  "Create a new template folder in the systems temp directory."
  []
  (let [base-dir (io/file (System/getProperty "java.io.tmpdir"))
        base-name (str "billing-" (System/currentTimeMillis) "-" (long (rand 1000000000)))
        tmp-dir (io/file (str base-dir "/" base-name))]
    (.mkdir tmp-dir)
    tmp-dir))

(defn write-template-file [folder template]
  (let [s (seq template)
        filename (name (first s))
        destination (io/file folder filename)]
    (printf "copying from %s to %s\n" filename destination)
    (when-not (.isDirectory (io/file (second s)))
     (if (instance? java.io.File (second s))
       (io/copy (second s) destination)
       (spit destination (second s))))))

(defn get-tmp-enviroment [tmp-dir templates]
  (doall (map (partial write-template-file tmp-dir) templates)))

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
    (println "Compiling template, first run")
    @(exec/sh (conj latex-cmd "main.tex") options)
    (println "Compiling template, second run")
    @(exec/sh (conj latex-cmd "main.tex") options)))

(defn print-converter [dir]
  (map (comp print slurp) (read-template-files dir)))

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
        (java.text.SimpleDateFormat. "dd.MM.yyyy hh:mm:ss") date)))

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
                    (:target-number c) " & "
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
  Kosten (Netto) & & & & ##fees## \\euro\\\\
  Umsatzsteuer 19\\% & & & & ##ust## \\euro \\\\
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
        symbols {:fees (fees->ffees (:billing-fees bill))
                 :brutto (fees->ffees (* 1.19 (:billing-fees bill)))
                 :ust (fees->ffees (* 0.19 (:billing-fees bill)))
                 :con-points (str (str/join "\\\\"
                                            (map #(clojure.string/trim (str (str/join " & " (vals %)) "\\euro")) conss)))}]
    (pr (:con-points symbols))
    (replace-symbols symbols overview-template)))

(defn fill-template [files symbols]
  (into {}
        (for [file files]
          (let [f (val file)]
            (if (or (str/ends-with? (.getName f) "png") (.isDirectory (io/file f)))
             file
             {(key file)
              (replace-empty-symbols (replace-symbols symbols (slurp f)))})))))

(defn get-pdf-name
  "Get the path of the result file and maybe create the output directory."
  [outdir name]
  (when-not (.exists (io/file outdir))
    (.mkdir (io/file outdir)))
  (io/file outdir (str name ".pdf")))

(defn prepare-bill
  [template-files bill]
  (let* [sym (conj (dissoc bill :connection-points :customer) (:customer bill))
         sym (assoc sym :billing-fees (fees->ffees (* 1.19 (:billing-fees sym))))
        evn (str/join (create-evn bill))
        overview (str/join (create-overview bill))
        template (fill-template template-files sym)]
    (pr sym)
    (conj template {:evn.tex evn :overview.tex overview})))

(defn create-bill
  "Create a new bill in a clean temporary enviroment and return the resulting pdf."
  ([template-files outdir bill]
   (let [temp-dir (mk-tmp-dir)
         destination-file (get-pdf-name outdir (:billing-name bill))
         billing-files (prepare-bill template-files bill)]
     (get-tmp-enviroment temp-dir billing-files)
     (convert latex-converter temp-dir)
     (let [res (io/copy (io/file temp-dir "main.pdf") destination-file)]
       (println "copying " (.getName destination-file)  " to " (.getPath destination-file))))))

(defn create-bills [template-files outdir bills]
  (let [f (partial create-bill template-files outdir)]
    (map f bills)))
