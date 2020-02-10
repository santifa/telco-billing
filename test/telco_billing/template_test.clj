(ns telco-billing.template-test
  (:require [telco.template :as sut]
            [clojure.test :as t]))

(t/deftest read-template-files-test
  (let [files (sut/read-template-files "resources/evn-template")]
    (t/is (= 1 (count files)))
    (t/is (= [(java.io.File. "resources/evn-template/main.tex")] files))))
