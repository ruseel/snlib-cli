(ns snlib.fixture-flow-test
  (:require
   [clj-http.client]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [snlib.core :as core]))

(defn- fixture-html
  [relative-path]
  (slurp (io/file "test/fixtures/snlib" relative-path)))

(defn- with-request-stub
  [responses f]
  (let [calls (atom [])
        queue (atom (vec responses))
        request-var (ns-resolve 'clj-http.client 'request)]
    (with-redefs-fn
      {request-var
       (fn [req]
         (let [normalized-req (update req :url
                                      (fn [url]
                                        (if (str/starts-with? (or url "") "http")
                                          (.getPath (java.net.URI. url))
                                          url)))]
           (swap! calls conj normalized-req))
         (if-let [resp (first @queue)]
           (do
             (swap! queue subvec 1)
             resp)
           (throw (ex-info "No more stubbed responses." {:request req}))))}
      #(f calls))))

(defn- with-submit-allowed
  [submit-kind f]
  (let [guard-var (or (ns-resolve 'snlib.core
                                  (case submit-kind
                                    :hope-book 'hope-book-submit-allowed?
                                    :interloan 'interloan-submit-allowed?))
                      (throw (ex-info "Missing private var"
                                      {:submit-kind submit-kind})))]
    (with-redefs-fn {guard-var (constantly true)}
      f)))

(deftest search-books는-상호대차-대상-전체-HTML-픽스처를-파싱한다
  (let [client (core/create-client)
        html (fixture-html "search-books/interloan-target.html")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [_calls]
        (let [result (core/search-books! client {:keyword "삼국지 1"
                                                 :per-page 20})]
          (is (:ok? result))
          (is (= 12 (get-in result [:data :total-count])))
          (is (= 10 (count (get-in result [:data :items]))))
          (is (= {:title "삼국지 1"
                  :author "나관중 지음"
                  :publisher "가상출판사"
                  :publish-year "2024"
                  :manage-code "MG"
                  :reg-no "FIC000000101"}
                 (get-in result [:data :items 0])))
          (is (= {:title "삼국지 1"
                  :author "나관중 지음"
                  :publisher "가상출판사"
                  :publish-year "2024"
                  :manage-code "MB"
                  :reg-no "FIC000000110"}
                 (get-in result [:data :items 9]))))))))

(deftest search-books는-희망도서-대상-전체-HTML-픽스처를-파싱한다
  (let [client (core/create-client)
        html (fixture-html "search-books/hope-book-target.html")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [_calls]
        (let [result (core/search-books! client {:keyword "삼국지 2"})]
          (is (:ok? result))
          (is (= 1 (get-in result [:data :total-count])))
          (is (= [{:title "삼국지 2, 적벽대전"
                   :author "나관중 지음 ; 테스트번역 옮김"
                   :publisher "가상출판사"
                   :publish-year "2024"
                   :manage-code "MP"
                   :reg-no "FIC000000202"}]
                 (get-in result [:data :items]))))))))

(deftest hope-book-request는-전체-페이지-픽스처의-기본값을-준비-페이로드에-반영한다
  (let [client (core/create-client)
        page-html (fixture-html "hope-book-request/page.html")
        submit-html (fixture-html "hope-book-request/submit-samgukji-2.html")]
    (with-submit-allowed
      :hope-book
      #(with-request-stub
         [{:status 200 :headers {"content-type" "text/html"} :body page-html}
          {:status 200 :headers {"content-type" "text/html"} :body submit-html}]
         (fn [calls]
           (let [result (core/hope-book-request! client {:manage-code "MU"
                                                         :request {:title "삼국지 2"
                                                                   :author "나관중"
                                                                   :publisher "가상출판사"
                                                                   :publish-year "2024"
                                                                   :ea-isbn "9780000000002"
                                                                   :price "25600"
                                                                   :sms-receipt-yn "Y"}
                                                         :submit? true
                                                         :allow-submit? false})]
             (is (:ok? result))
             (is (= :ok (:status result)))
             (is (= "신청이 완료 되었습니다."
                    (get-in result [:data :result-message])))
             (is (= "masked@example.com"
                    (get-in result [:data :prepared-payload "email"])))
             (is (= "010"
                    (get-in result [:data :prepared-payload "mobileNo1"])))
             (is (= "1234"
                    (get-in result [:data :prepared-payload "mobileNo2"])))
             (is (= "5678"
                    (get-in result [:data :prepared-payload "mobileNo3"])))
             (is (= "010-1234-5678"
                    (get-in result [:data :prepared-payload "handPhone"])))
             (is (= "Y"
                    (get-in result [:data :prepared-payload "smsReceiptYn"])))
             (testing "submit request keeps live-page defaults plus explicit fields"
               (is (= "/intro/menu/10045/program/30011/hopeBookApplyProc.do"
                      (:url (second @calls))))
               (is (= "masked@example.com"
                      (get-in (second @calls) [:form-params "email"])))
               (is (= "010-1234-5678"
                      (get-in (second @calls) [:form-params "handPhone"])))
               (is (= "MU"
                      (get-in (second @calls) [:form-params "manageCode"])))))))))

(deftest interloan-request는-전체-팝업-픽스처로-제출-응답을-처리한다
  (let [client (core/create-client)
        popup-html (fixture-html "interloan-request/popup-samgukji-1.html")
        submit-html (fixture-html "interloan-request/submit-samgukji-1.html")]
    (with-submit-allowed
      :interloan
      #(with-request-stub
         [{:status 200 :headers {"content-type" "text/html"} :body popup-html}
          {:status 200 :headers {"content-type" "text/html"} :body submit-html}]
         (fn [calls]
           (let [result (core/interloan-request! client {:manage-code "MG"
                                                         :reg-no "FIC000000101"
                                                         :apl-lib-code "141484"
                                                         :submit? true
                                                         :allow-submit? false})]
             (is (false? (:ok? result)))
             (is (= :submit-failed (:status result)))
             (is (= "비치중인 자료만 상호대차 신청이 가능합니다.[현재 책상태: 관외대출자료]"
                    (get-in result [:data :result-message])))
             (is (= "141142"
                    (get-in result [:data :prepared-payload "giveLibCode"])))
             (is (= "9999999999"
                    (get-in result [:data :prepared-payload "userKey"])))
             (testing "submit request uses popup-derived form values"
               (is (= "/intro/doorae/bandLillApplyPopProc.do"
                      (:url (second @calls))))
               (is (= {"manageCode" "MG"
                       "regNo" "FIC000000101"
                       "giveLibCode" "141142"
                       "appendixApplyYn" "N"
                       "aplLibCode" "141484"
                       "userKey" "9999999999"}
                      (:form-params (second @calls))))))))))))
