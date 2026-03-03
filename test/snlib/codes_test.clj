(ns snlib.codes-test
  (:require
   [clojure.test :refer [deftest is]]
   [snlib.codes :as codes]))

(deftest normalize-and-validate-manage-code
  (is (= "MA" (codes/normalize-manage-code " ma ")))
  (is (codes/valid-manage-code? "MA"))
  (is (not (codes/valid-manage-code? "M1"))))

(deftest normalize-and-validate-lib-code
  (is (= "141484" (codes/normalize-lib-code " 141484 ")))
  (is (codes/valid-lib-code? "141484"))
  (is (not (codes/valid-lib-code? "14148A"))))

(deftest known-lib-code-lookup
  (is (codes/known-lib-code? "141484"))
  (is (not (codes/known-lib-code? "000000"))))

(deftest normalize-interloan-input-and-invalid-fields
  (let [normalized (codes/normalize-interloan-input {:manage-code " mb "
                                                     :apl-lib-code " 14148A "
                                                     :give-lib-code " 141484 "
                                                     :submit? true})]
    (is (= "MB" (:manage-code normalized)))
    (is (= "14148A" (:apl-lib-code normalized)))
    (is (= [:apl-lib-code]
           (codes/invalid-interloan-input normalized)))))
