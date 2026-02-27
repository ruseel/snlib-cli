(ns snlib.core-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [snlib.core :as core]))

(defn- with-request-stub
  [responses f]
  (let [calls (atom [])
        queue (atom (vec responses))
        request-var (ns-resolve 'snlib.core 'request)]
    (with-redefs-fn
      {request-var
       (fn [_client req]
         (swap! calls conj req)
         (if-let [resp (first @queue)]
           (do
             (swap! queue subvec 1)
             resp)
           (throw (ex-info "No more stubbed responses." {:request req}))))}
      #(f calls))))

(deftest login-success-uses-har-request-contract
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {} :body "<html>login page</html>"}
       {:status 302 :headers {"location" "/intro/main/index.do"} :body ""}
       {:status 200 :headers {"content-type" "text/html"} :body "<html><body>mypage</body></html>"}]
      (fn [calls]
        (let [result (core/login! client {:user-id "alice"
                                          :password "secret"})]
          (is (= {:ok? true
                  :status :ok
                  :data {:authenticated? true
                         :user-id "alice"}
                  :error nil}
                 result))
          (is (core/authenticated? client))
          (is (= 3 (count @calls)))
          (testing "GET login page"
            (is (= :get (:method (nth @calls 0))))
            (is (= "/intro/memberLogin.do" (:url (nth @calls 0)))))
          (testing "POST login submit with expected form keys"
            (is (= :post (:method (nth @calls 1))))
            (is (= "/intro/menu/10068/program/30025/memberLoginProc.do"
                   (:url (nth @calls 1))))
            (is (= "alice" (get-in (nth @calls 1) [:form-params "userId"])))
            (is (= "secret" (get-in (nth @calls 1) [:form-params "password"])))
            (is (= "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw=="
                   (get-in (nth @calls 1) [:form-params "returnUrl"]))))
          (testing "GET loan status verify"
            (is (= :get (:method (nth @calls 2))))
            (is (= "/intro/menu/10060/program/30019/mypage/loanStatusList.do"
                   (:url (nth @calls 2))))))))))

(deftest login-detects-requires-login-on-verify-page
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {} :body "<html>login page</html>"}
       {:status 302 :headers {"location" "/intro/main/index.do"} :body ""}
       {:status 200
        :headers {"content-type" "text/html"}
        :body "<script>location.href='/intro/memberLogin.do';</script>"}]
      (fn [_calls]
        (let [result (core/login! client {:user-id "alice"
                                          :password "secret"})]
          (is (= {:ok? false
                  :status :requires-login
                  :data {:authenticated? false
                         :user-id "alice"}
                  :error nil}
                 result))
          (is (not (core/authenticated? client))))))))

(deftest login-validates-input-before-http
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {} :body "<html>unused</html>"}]
      (fn [calls]
        (let [result (core/login! client {:user-id "alice"})]
          (is (= {:ok? false
                  :status :invalid-input
                  :data nil
                  :error {:code :missing-required-input
                          :message "Missing required input: password"}}
                 result))
          (is (zero? (count @calls))))))))

(deftest search-books-uses-har-request-contract-and-parses-result
  (let [client (core/create-client)
        html (str
              "<p class='rtitle'>검색 결과 총 <strong class='themeFC'>657건</strong></p>"
              "<ul class='resultList imageType'>"
              "<li>"
              "<dt class='tit'><a>프랭클린 자서전</a></dt>"
              "<dd class='author'>"
              "<span>저자 : Benjamin Franklin 지음</span>"
              "<span>발행자: 내외신서 ,</span>"
              "<span>발행연도: 1993</span>"
              "</dd>"
              "<div class='stateArea'>"
              "<a href='#btn' onclick=\"javascript:fnBandLillApplyPop('MA','CEM000050087'); return false;\">상호대차신청</a>"
              "</div>"
              "</li>"
              "</ul>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/search-books! client {:keyword "프랭클린"
                                                 :library-code ["MA" "MS"]
                                                 :page 2
                                                 :per-page 5})]
          (is (= {:ok? true
                  :status :ok
                  :data {:items [{:title "프랭클린 자서전"
                                  :author "Benjamin Franklin 지음"
                                  :publisher "내외신서"
                                  :publish-year "1993"
                                  :manage-code "MA"
                                  :reg-no "CEM000050087"}]
                         :page 2
                         :total-count 657}
                  :error nil}
                 result))
          (is (= 1 (count @calls)))
          (testing "GET search request path and core query params"
            (is (= :get (:method (first @calls))))
            (is (= "/intro/menu/10041/program/30009/plusSearchResultList.do"
                   (:url (first @calls))))
            (is (= "SIMPLE" (get-in (first @calls) [:query-params "searchType"])))
            (is (= "BOOK" (get-in (first @calls) [:query-params "searchCategory"])))
            (is (= "ALL" (get-in (first @calls) [:query-params "searchKey"])))
            (is (= "프랭클린" (get-in (first @calls) [:query-params "searchKeyword"])))
            (is (= "SIMILAR" (get-in (first @calls) [:query-params "searchSort"])))
            (is (= "DESC" (get-in (first @calls) [:query-params "searchOrder"])))
            (is (= "5" (get-in (first @calls) [:query-params "searchRecordCount"])))
            (is (= "2" (get-in (first @calls) [:query-params "currentPageNo"])))
            (is (= ["MA" "MS"]
                   (get-in (first @calls) [:query-params "searchLibraryArr"])))))))))

(deftest search-books-validates-input-before-http
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html>unused</html>"}]
      (fn [calls]
        (let [result (core/search-books! client {:keyword ""})]
          (is (= {:ok? false
                  :status :invalid-input
                  :data nil
                  :error {:code :missing-required-input
                          :message "Missing required input: keyword"}}
                 result))
          (is (zero? (count @calls))))))))

(deftest search-books-parses-interloan-params-when-link-is-not-first-onclick
  (let [client (core/create-client)
        html (str
              "<ul class='resultList imageType'>"
              "<li>"
              "<dt class='tit'><a>혼모노</a></dt>"
              "<dd class='author'><span>저자 : 성해나 지음</span></dd>"
              "<div class='stateArea'>"
              "<a href='#btn' onclick=\"javascript:fnSearchResultDetail(1,2,'BO'); return false;\">상세</a>"
              "<a href='#btn' onclick=\"javascript:fnBandLillApplyPop('MA','CEM000903366'); return false;\">상호대차신청</a>"
              "</div>"
              "</li>"
              "</ul>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [_calls]
        (let [result (core/search-books! client {:keyword "혼모노"})]
          (is (= "MA"
                 (get-in result [:data :items 0 :manage-code])))
          (is (= "CEM000903366"
                 (get-in result [:data :items 0 :reg-no]))))))))

(deftest loan-status-uses-har-request-contract-and-parses-active-loans
  (let [client (core/create-client)
        html (str
              "<table class='tbList'>"
              "<thead><tr>"
              "<th>번호</th><th>서명</th><th>대출일</th><th>반납예정일</th><th>대출상태</th><th>연장</th>"
              "</tr></thead>"
              "<tbody>"
              "<tr><td>1</td><td>클린 코드</td><td>2026-02-10</td><td>2026-02-24</td><td>대출중</td><td>가능</td></tr>"
              "<tr><td>2</td><td>리팩터링</td><td>2026-01-01</td><td>2026-01-14</td><td>반납완료</td><td>불가</td></tr>"
              "</tbody>"
              "</table>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/loan-status! client {:include-history? false})]
          (is (= {:ok? true
                  :status :ok
                  :data {:loans [{:title "클린 코드"
                                  :loan-date "2026-02-10"
                                  :due-date "2026-02-24"
                                  :return-status "대출중"
                                  :renewable? true}]
                         :count 1}
                  :error nil}
                 result))
          (is (= 1 (count @calls)))
          (testing "GET loan status request path and no query params"
            (is (= :get (:method (first @calls))))
            (is (= "/intro/menu/10060/program/30019/mypage/loanStatusList.do"
                   (:url (first @calls))))
            (is (nil? (:query-params (first @calls))))))))))

(deftest loan-status-include-history-returns-all-loans
  (let [client (core/create-client)
        html (str
              "<table>"
              "<thead><tr><th>서명</th><th>대출일</th><th>반납예정일</th><th>상태</th><th>연장</th></tr></thead>"
              "<tbody>"
              "<tr><td>클린 코드</td><td>2026-02-10</td><td>2026-02-24</td><td>대출중</td><td>Y</td></tr>"
              "<tr><td>리팩터링</td><td>2026-01-01</td><td>2026-01-14</td><td>반납완료</td><td>N</td></tr>"
              "</tbody>"
              "</table>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [_calls]
        (let [result (core/loan-status! client {:include-history? true})]
          (is (= 2 (get-in result [:data :count])))
          (is (= 2 (count (get-in result [:data :loans]))))
          (is (= false (get-in result [:data :loans 1 :renewable?]))))))))

(deftest loan-status-detects-logged-out-page
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200
        :headers {"content-type" "text/html"}
        :body "<script>location.href='/intro/memberLogin.do';</script>"}]
      (fn [_calls]
        (let [result (core/loan-status! client {})]
          (is (= {:ok? false
                  :status :requires-login
                  :data {:loans []
                         :count 0}
                  :error nil}
                 result)))))))

(deftest interlibrary-loan-request-uses-har-popup-contract
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html><body>popup</body></html>"}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "MB"
                                                               :reg-no "BEM000133237"})]
          (is (= {:ok? true
                  :status :ok
                  :data {:prepared-payload {"manageCode" "MB"
                                            "regNo" "BEM000133237"
                                            "giveLibCode" ""
                                            "appendixApplyYn" "N"
                                            "aplLibCode" ""
                                            "userKey" ""}
                         :submit-attempted? false
                         :submit-blocked? false
                         :result-message nil}
                  :error nil}
                 result))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= "/intro/doorae/bandLillApplyPop.do"
                 (:url (first @calls))))
          (is (= "MB" (get-in (first @calls) [:query-params "manageCode"])))
          (is (= "BEM000133237" (get-in (first @calls) [:query-params "regNo"]))))))))

(deftest interlibrary-loan-request-blocks-submit-by-default
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200
        :headers {"content-type" "text/html"}
        :body (str
               "<html><body>"
               "<input type='hidden' name='giveLibCode' value='141052'/>"
               "<select name='userKey'><option value='1060272451'>ruseel</option></select>"
               "</body></html>")}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "MB"
                                                               :reg-no "BEM000133237"
                                                               :apl-lib-code "141484"
                                                               :submit? true})]
          (is (= false (:ok? result)))
          (is (= :blocked (:status result)))
          (is (= true (get-in result [:data :submit-attempted?])))
          (is (= true (get-in result [:data :submit-blocked?])))
          (is (= :write-blocked (get-in result [:error :code])))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls)))))))))

(deftest interlibrary-loan-request-submit-uses-har-submit-contract-when-allowed
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200
        :headers {"content-type" "text/html"}
        :body (str
               "<html><body>"
               "<input type='hidden' name='giveLibCode' value='141052'/>"
               "<select name='userKey'><option value='1060272451'>ruseel</option></select>"
               "</body></html>")}
       {:status 200
        :headers {"content-type" "text/html"}
        :body "<html><head><title>상호대차 신청결과</title></head><body>상호대차 신청이 완료되었습니다.</body></html>"}]
      (fn [calls]
        (let [result (core/interloan-request! client {:manage-code "MB"
                                                      :reg-no "BEM000133237"
                                                      :apl-lib-code "141484"
                                                      :appendix-apply-yn "N"
                                                      :submit? true
                                                      :allow-submit? true})]
          (is (= true (:ok? result)))
          (is (= :ok (:status result)))
          (is (= "상호대차 신청이 완료되었습니다."
                 (get-in result [:data :result-message])))
          (is (= 2 (count @calls)))
          (is (= :post (:method (second @calls))))
          (is (= "/intro/doorae/bandLillApplyPopProc.do"
                 (:url (second @calls))))
          (is (= {"manageCode" "MB"
                  "regNo" "BEM000133237"
                  "giveLibCode" "141052"
                  "appendixApplyYn" "N"
                  "aplLibCode" "141484"
                  "userKey" "1060272451"}
                 (:form-params (second @calls))))
          (is (= "https://snlib.go.kr"
                 (get-in (second @calls) [:headers "Origin"])))
          (is (str/includes? (get-in (second @calls) [:headers "Referer"])
                             "manageCode=MB"))
          (is (str/includes? (get-in (second @calls) [:headers "Referer"])
                             "regNo=BEM000133237")))))))

(deftest interlibrary-loan-request-validates-submit-required-input-before-http
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html>unused</html>"}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "MB"
                                                               :reg-no "BEM000133237"
                                                               :submit? true})]
          (is (= {:ok? false
                  :status :invalid-input
                  :data nil
                  :error {:code :missing-required-input
                          :message "Missing required input: apl-lib-code"}}
                 result))
          (is (zero? (count @calls))))))))

(deftest interlibrary-loan-request-fails-when-popup-misses-derived-submit-values
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html><body>popup</body></html>"}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "MB"
                                                               :reg-no "BEM000133237"
                                                               :apl-lib-code "141484"
                                                               :submit? true})]
          (is (= {:ok? false
                  :status :invalid-input
                  :data nil
                  :error {:code :missing-required-input
                          :message "Missing required input: give-lib-code, user-key"}}
                 result))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls)))))))))

(deftest hope-book-request-prepares-payload-from-page-form
  (let [client (core/create-client)
        html (str
              "<form id='topSearchForm' method='post'>"
              "<input type='text' name='searchKeyword' value=''/>"
              "</form>"
              "<form id='registForm' name='registForm' method='post'>"
              "<input type='hidden' name='manageCode' value='MS'/>"
              "<input type='hidden' name='smsReceiptYn' value='N'/>"
              "<input type='text' name='title' value=''/>"
              "<input type='text' name='author' value=''/>"
              "</form>"
              "<script>var submitPath='/intro/menu/10045/program/30011/hopeBookApplyProc.do';</script>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/hope-book-request! client {:book-info {:title "테스트 도서"
                                                                   :author "홍길동"
                                                                   :publisher "테스트출판"
                                                                   :publish-year 2024
                                                                   :ea-isbn "9781234567890"}
                                                      :applicant-info {:email "tester@example.com"
                                                                       :sms-receipt-yn "Y"
                                                                       :mobile-no1 "010"
                                                                       :mobile-no2 "1234"
                                                                       :mobile-no3 "5678"}})]
          (is (= true (:ok? result)))
          (is (= :ok (:status result)))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= "/intro/menu/10045/program/30011/hopeBookApply.do"
                 (:url (first @calls))))
          (is (= "MS" (get-in result [:data :prepared-payload "manageCode"])))
          (is (= "테스트 도서" (get-in result [:data :prepared-payload "title"])))
          (is (= "테스트출판" (get-in result [:data :prepared-payload "publisher"])))
          (is (= "2024" (get-in result [:data :prepared-payload "publishYear"])))
          (is (= "Y" (get-in result [:data :prepared-payload "smsReceiptYn"])))
          (is (= "010" (get-in result [:data :prepared-payload "mobileNo1"]))))))))

(deftest hope-book-request-blocks-submit-by-default
  (let [client (core/create-client)
        html (str
              "<form id='registForm' name='registForm' method='post'>"
              "<input type='hidden' name='manageCode' value='MS'/>"
              "<input type='text' name='title' value=''/>"
              "<input type='text' name='author' value=''/>"
              "</form>"
              "<script>location.href='/intro/menu/10045/program/30011/hopeBookApplyProc.do';</script>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/hope-book-request! client {:book-info {:title "테스트 도서"
                                                                   :author "홍길동"}
                                                      :submit? true})]
          (is (= false (:ok? result)))
          (is (= :blocked (:status result)))
          (is (= true (get-in result [:data :submit-attempted?])))
          (is (= true (get-in result [:data :submit-blocked?])))
          (is (= :write-blocked (get-in result [:error :code])))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls)))))))))

(deftest hope-book-request-submit-uses-har-contract-when-allowed
  (let [client (core/create-client)
        html (str
              "<form id='registForm' name='registForm' method='post'>"
              "<input type='hidden' name='manageCode' value='MS'/>"
              "<input type='text' name='title' value=''/>"
              "<input type='text' name='author' value=''/>"
              "</form>"
              "<script>var submitPath='/intro/menu/10045/program/30011/hopeBookApplyProc.do';</script>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}
       {:status 302
        :headers {"location" "/intro/main/index.do"}
        :body "<html><head><title>희망도서 신청 완료</title></head><body></body></html>"}]
      (fn [calls]
        (let [result (core/hope-book-request! client {:book-info {:title "클린 코드"
                                                                   :author "Robert C. Martin"
                                                                   :price 32000}
                                                      :applicant-info {:email "tester@example.com"
                                                                       :sms-receipt-yn "Y"}
                                                      :submit? true
                                                      :allow-submit? true})]
          (is (= true (:ok? result)))
          (is (= :ok (:status result)))
          (is (= "희망도서 신청 완료" (get-in result [:data :result-message])))
          (is (= 2 (count @calls)))
          (is (= :post (:method (second @calls))))
          (is (= "/intro/menu/10045/program/30011/hopeBookApplyProc.do"
                 (:url (second @calls))))
          (is (= "MS" (get-in (second @calls) [:form-params "manageCode"])))
          (is (= "클린 코드" (get-in (second @calls) [:form-params "title"])))
          (is (= "32000" (get-in (second @calls) [:form-params "price"])))
          (is (= "Y" (get-in (second @calls) [:form-params "smsReceiptYn"])))
          (is (= "https://snlib.go.kr/intro/menu/10045/program/30011/hopeBookApply.do"
                 (get-in (second @calls) [:headers "Referer"]))))))))
