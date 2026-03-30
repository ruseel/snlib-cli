(ns snlib.codes-test
  (:require
   [clojure.test :refer [deftest is]]
   [snlib.codes :as codes]))

(deftest 관리-코드는-공백을-제거하고-대문자로-정규화한-뒤-두-자리-영문자만-유효하다
  (is (= "MA" (codes/normalize-manage-code " ma ")))
  (is (codes/valid-manage-code? "MA"))
  (is (not (codes/valid-manage-code? "M1"))))

(deftest 도서관-코드는-공백만-정리하고-여섯-자리-숫자일-때만-유효하다
  (is (= "141484" (codes/normalize-lib-code " 141484 ")))
  (is (codes/valid-lib-code? "141484"))
  (is (not (codes/valid-lib-code? "14148A"))))

(deftest 알려진-도서관-코드인지-등록된-목록으로-판단한다
  (is (codes/known-lib-code? "141484"))
  (is (not (codes/known-lib-code? "000000"))))

(deftest 상호대차-입력은-코드를-정규화하고-제출-시-잘못된-신청-도서관-코드를-검출한다
  (let [normalized (codes/normalize-interloan-input {:manage-code " mb "
                                                     :apl-lib-code " 14148A "
                                                     :give-lib-code " 141484 "
                                                     :submit? true})]
    (is (= "MB" (:manage-code normalized)))
    (is (= "14148A" (:apl-lib-code normalized)))
    (is (= [:apl-lib-code]
           (codes/invalid-interloan-input normalized)))))
