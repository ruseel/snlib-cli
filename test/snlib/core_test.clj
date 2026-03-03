(ns snlib.core-test
  (:require
   [clj-http.client]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [snlib.core :as core]))

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
          (is (true? (:ok? result)))
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

(deftest interlibrary-loan-request-normalizes-manage-code-before-http
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html><body>popup</body></html>"}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code " mb "
                                                              :reg-no "BEM000133237"})]
          (is (true? (:ok? result)))
          (is (= "MB"
                 (get-in (first @calls) [:query-params "manageCode"]))))))))

(deftest interlibrary-loan-request-validates-code-format-before-http
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body "<html>unused</html>"}]
      (fn [calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "M1"
                                                              :reg-no "BEM000133237"
                                                              :apl-lib-code "14148A"
                                                              :submit? true})]
          (is (= {:ok? false
                  :status :invalid-input
                  :data nil
                  :error {:code :invalid-input-format
                          :message "Invalid input format: manage-code, apl-lib-code"}}
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
          (is (true? (:ok? result)))
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
          (is (true? (:ok? result)))
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

          ;; ---------------------------------------------------------------------------
          ;; my-info
          ;; ---------------------------------------------------------------------------

(deftest my-info-parses-member-page
  (let [client (core/create-client)
        html (str
               "<div class='myInfo'>"
               "<div class='memType'><strong class='member typeA themeColor'>정회원</strong></div>"
               "<div class='myInfoList'><ul class='dot-list'>"
               "<li>아이디 : testuser</li>"
               "<li>회원번호 : 12345678</li>"
               "<li>회원가입일 : 2020-01-01</li>"
               "<li>개인정보동의 만료일 : 2027-01-01</li>"
               "<li>휴대폰번호 : 010-1***-5678 (SMS수신)</li>"
               "<li>이메일주소 : te****@example.com</li>"
               "</ul></div></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/my-info! client {})]
          (is (true? (:ok? result)))
          (is (= "testuser" (get-in result [:data :user-id])))
          (is (= "12345678" (get-in result [:data :member-no])))
          (is (= "정회원" (get-in result [:data :member-type])))
          (is (= "010-1***-5678 (SMS수신)" (get-in result [:data :phone])))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= "/intro/menu/10055/program/30017/mypage/myInfo.do"
                (:url (first @calls)))))))))

(deftest my-info-detects-logged-out
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200
        :headers {"content-type" "text/html"}
        :body "<script>location.href='/intro/memberLogin.do';</script>"}]
      (fn [_calls]
        (let [result (core/my-info! client {})]
          (is (= false (:ok? result)))
          (is (= :requires-login (:status result))))))))

          ;; ---------------------------------------------------------------------------
          ;; loan-history
          ;; ---------------------------------------------------------------------------

(deftest loan-history-parses-article-list
  (let [client (core/create-client)
        html (str
               "<div class='boardFilter'><p class='count'>대출이력건수 : <span class='themeFC'>2</span>건</p></div>"
               "<div class='articleWrap'><ul class='article-list'>"
               "<li>"
               "<p class='title'><a href='#'>클린 코드</a></p>"
               "<p class='info'><span>등록번호 : CEM000001</span> <span>청구기호 : 005-ㅁ123</span></p>"
               "<p class='info'><span>소장도서관 : 중앙도서관</span> <span>자료실 : [중앙]1층</span></p>"
               "<p class='info'><span class='status finish'>상태 : <em>반납</em></span> <span>대출일 : 2026.01.01</span> <span>반납일 : 2026.01.15</span></p>"
               "</li>"
               "</ul></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/loan-history! client {})]
          (is (true? (:ok? result)))
          (is (= 2 (get-in result [:data :count])))
          (is (= 1 (count (get-in result [:data :loans]))))
          (let [loan (first (get-in result [:data :loans]))]
            (is (= "클린 코드" (:title loan)))
            (is (= "CEM000001" (:reg-no loan)))
            (is (= "중앙도서관" (:library loan)))
            (is (= "반납" (:return-status loan)))
            (is (= "2026.01.01" (:loan-date loan)))
            (is (= "2026.01.15" (:return-date loan))))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= "/intro/menu/10062/program/30021/mypage/loanHistoryList.do"
                (:url (first @calls)))))))))

          ;; ---------------------------------------------------------------------------
          ;; reservation-status
          ;; ---------------------------------------------------------------------------

(deftest reservation-status-empty-list
  (let [client (core/create-client)
        html (str
               "<div class='boardFilter'><p class='count'>예약현황건수 : <span class='themeFC'>0</span>건</p></div>"
               "<div class='articleWrap'><ul class='article-list'>"
               "<li class='emptyNote'>내역이 존재하지 않습니다.</li>"
               "</ul></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/reservation-status! client {})]
          (is (true? (:ok? result)))
          (is (= 0 (get-in result [:data :count])))
          (is (= [] (get-in result [:data :reservations])))
          (is (= 1 (count @calls)))
          (is (= "/intro/menu/10061/program/30020/mypage/reservationStatusList.do"
                (:url (first @calls)))))))))

          ;; ---------------------------------------------------------------------------
          ;; interloan-status
          ;; ---------------------------------------------------------------------------

(deftest interloan-status-parses-article-list
  (let [client (core/create-client)
        html (str
               "<div class='boardFilter'><p class='count'>상호대차건수 : <span class='themeFC'>2</span>건</p></div>"
               "<div class='articleWrap'><ul class='article-list'>"
               "<li>"
               "<p class='title'>테스트 도서</p>"
               "<p class='info'><span>등록번호 : CEM000001</span> <span>청구기호 : 005-ㅌ123</span></p>"
               "<p class='info'><span>제공도서관 : 중앙도서관 [중앙]1층</span> <span>수령도서관 : 운중도서관</span></p>"
               "<p class='info'><span class='status ready'>상태 : <em>요청중</em></span> <span>신청일 : 2026.02.28</span> <span>신청자 : 홍길동(hong) </span>"
               "<a href='#btn' onclick=\"javascript:fnDooraeCancelProc('12345'); return false;\" class='btn small themeBtn'>신청취소</a></p>"
               "</li>"
               "</ul></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/interloan-status! client {})]
          (is (true? (:ok? result)))
          (is (= 2 (get-in result [:data :count])))
          (let [req (first (get-in result [:data :requests]))]
            (is (= "테스트 도서" (:title req)))
            (is (= "CEM000001" (:reg-no req)))
            (is (= "요청중" (:status req)))
            (is (= "12345" (:cancel-key req)))
            (is (= "운중도서관" (:apl-library req))))
          (is (= 1 (count @calls)))
          (is (= "/intro/bandLillStatusList.do"
                (:url (first @calls)))))))))

          ;; ---------------------------------------------------------------------------
          ;; hope-book-list / hope-book-detail
          ;; ---------------------------------------------------------------------------

(deftest hope-book-list-parses-article-list
  (let [client (core/create-client)
        html (str
               "<div class='boardFilter'><p class='count'>희망도서건수 : <span class='themeFC'>2</span>건</p></div>"
               "<div class='articleWrap'><ul class='article-list'>"
               "<li>"
               "<p class='title'><a href='#link' onclick=\"javascript:hopeBookDetail(12345); return false;\">테스트 도서</a></p>"
               "<p class='info'><span>도서관 : 운중도서관</span> <span>신청일 : 2025.04.12</span></p>"
               "<p class='info'><span class='status ready'>상태 : <em>소장중</em></span></p>"
               "</li>"
               "</ul></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/hope-book-list! client {})]
          (is (true? (:ok? result)))
          (is (= 2 (get-in result [:data :count])))
          (let [item (first (get-in result [:data :items]))]
            (is (= "테스트 도서" (:title item)))
            (is (= "12345" (:rec-key item)))
            (is (= "운중도서관" (:library item)))
            (is (= "소장중" (:status item))))
          (is (= 1 (count @calls)))
          (is (= "/intro/menu/10065/program/30011/mypage/hopeBookList.do"
                (:url (first @calls)))))))))

(deftest hope-book-detail-parses-board-view-table
  (let [client (core/create-client)
        html (str
               "<table class='board-view'><tbody>"
               "<tr><th>희망도서명</th><td>테스트 도서</td></tr>"
               "<tr><th>저자</th><td>홍길동</td></tr>"
               "<tr><th>출판사</th><td>테스트출판</td></tr>"
               "<tr><th>출판연도</th><td>2024</td></tr>"
               "<tr><th>ISBN</th><td>9781234567890</td></tr>"
               "<tr><th>신청일</th><td>2025.04.12</td></tr>"
               "<tr><th>신청상태</th><td>소장중</td></tr>"
               "</tbody></table>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}]
      (fn [calls]
        (let [result (core/hope-book-detail! client {:rec-key "12345"})]
          (is (true? (:ok? result)))
          (is (= "테스트 도서" (get-in result [:data :title])))
          (is (= "홍길동" (get-in result [:data :author])))
          (is (= "9781234567890" (get-in result [:data :isbn])))
          (is (= "소장중" (get-in result [:data :status])))
          (is (= 1 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= "/intro/menu/10065/program/30011/mypage/hopeBookDetail.do"
                (:url (first @calls))))
          (is (= "12345" (get-in (first @calls) [:query-params "recKey"]))))))))

(deftest hope-book-detail-validates-rec-key
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 200 :headers {} :body "unused"}]
      (fn [calls]
        (let [result (core/hope-book-detail! client {})]
          (is (= false (:ok? result)))
          (is (= :invalid-input (:status result)))
          (is (zero? (count @calls))))))))

          ;; ---------------------------------------------------------------------------
          ;; basket-list
          ;; ---------------------------------------------------------------------------

(deftest basket-list-parses-books
  (let [client (core/create-client)
        main-html (str
                    "<div class='htitle'>기본<span class='normal'>(3건)</span></div>"
                    "<script>function x() { fnBasketGroupBookMore(99999); }</script>")
        book-html (str
                    "<div class='wishBookList'><ul class='listWrap'>"
                    "<li><div class='bookArea'><a href='#'>"
                    "<div class='bookData'>"
                    "<div class='book_name'>테스트 도서</div>"
                    "<div class='bk_writer'>홍길동 지음</div>"
                    "<div class='bk_publish'>테스트출판: 2024</div>"
                    "</div></a></div></li>"
                    "</ul></div>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body main-html}
       {:status 200 :headers {"content-type" "text/html"} :body book-html}]
      (fn [calls]
        (let [result (core/basket-list! client {})]
          (is (true? (:ok? result)))
          (is (= "99999" (get-in result [:data :group-key])))
          (is (= "기본" (get-in result [:data :group-name])))
          (is (= 3 (get-in result [:data :book-count])))
          (is (= 1 (get-in result [:data :count])))
          (let [book (first (get-in result [:data :books]))]
            (is (= "테스트 도서" (:title book)))
            (is (= "홍길동 지음" (:author book))))
          (is (= 2 (count @calls)))
          (is (= :get (:method (first @calls))))
          (is (= :post (:method (second @calls))))
          (is (= "99999" (get-in (second @calls) [:form-params "searchGroupKey"]))))))))

;; ---------------------------------------------------------------------------
;; remote-error / exception branches
;; ---------------------------------------------------------------------------

(deftest search-books-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 503 :headers {} :body "maintenance"}]
      (fn [_calls]
        (let [result (core/search-books! client {:keyword "테스트"})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :search-request-failed (get-in result [:error :code]))))))))

(deftest loan-status-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/loan-status! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :loan-status-request-failed (get-in result [:error :code]))))))))

(deftest my-info-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/my-info! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :my-info-request-failed (get-in result [:error :code]))))))))

(deftest loan-history-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/loan-history! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :loan-history-request-failed (get-in result [:error :code]))))))))

(deftest reservation-status-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/reservation-status! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :reservation-status-request-failed (get-in result [:error :code]))))))))

(deftest interloan-status-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/interloan-status! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :interloan-status-request-failed (get-in result [:error :code]))))))))

(deftest hope-book-list-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/hope-book-list! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :hope-book-list-request-failed (get-in result [:error :code]))))))))

(deftest hope-book-detail-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/hope-book-detail! client {:rec-key "12345"})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :hope-book-detail-request-failed (get-in result [:error :code]))))))))

(deftest basket-list-returns-remote-error-on-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 500 :headers {} :body "error"}]
      (fn [_calls]
        (let [result (core/basket-list! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :basket-list-request-failed (get-in result [:error :code]))))))))

(deftest interlibrary-loan-request-returns-remote-error-on-popup-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 503 :headers {} :body "maintenance"}]
      (fn [_calls]
        (let [result (core/interlibrary-loan-request! client {:manage-code "MB"
                                                              :reg-no "BEM000133237"})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :interloan-popup-request-failed (get-in result [:error :code]))))))))

(deftest hope-book-request-returns-remote-error-on-page-non-200
  (let [client (core/create-client)]
    (with-request-stub
      [{:status 503 :headers {} :body "maintenance"}]
      (fn [_calls]
        (let [result (core/hope-book-request! client {:book-info {:title "a" :author "b"}})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :hope-book-page-request-failed (get-in result [:error :code]))))))))

(deftest hope-book-request-submit-detects-failure-message
  (let [client (core/create-client)
        html (str
               "<form id='registForm' name='registForm' method='post'>"
               "<input type='hidden' name='manageCode' value='MS'/>"
               "<input type='text' name='title' value=''/>"
               "<input type='text' name='author' value=''/>"
               "</form>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body html}
       {:status 200
        :headers {"content-type" "text/html"}
        :body "<script>alert('희망도서 신청 실패');</script>"}]
      (fn [_calls]
        (let [result (core/hope-book-request! client {:book-info {:title "테스트 도서"
                                                                  :author "홍길동"}
                                                      :submit? true
                                                      :allow-submit? true})]
          (is (= false (:ok? result)))
          (is (= :submit-failed (:status result)))
          (is (= :hope-book-submit-failed (get-in result [:error :code]))))))))

(deftest basket-list-returns-remote-error-when-books-request-non-200
  (let [client (core/create-client)
        main-html (str
                    "<div class='htitle'>기본<span class='normal'>(3건)</span></div>"
                    "<script>function x() { fnBasketGroupBookMore(99999); }</script>")]
    (with-request-stub
      [{:status 200 :headers {"content-type" "text/html"} :body main-html}
       {:status 503 :headers {} :body "maintenance"}]
      (fn [_calls]
        (let [result (core/basket-list! client {})]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :basket-list-books-request-failed (get-in result [:error :code]))))))))

(deftest core-apis-return-http-request-failed-on-request-exception
  (let [client (core/create-client)
        ex (ex-info "boom" {})]
    (with-redefs [clj-http.client/request (fn [_req] (throw ex))]
      (doseq [[f opts]
              [[core/login! {:user-id "alice" :password "secret"}]
               [core/search-books! {:keyword "테스트"}]
               [core/loan-status! {}]
               [core/hope-book-request! {:book-info {:title "a" :author "b"}}]
               [core/interlibrary-loan-request! {:manage-code "MB" :reg-no "R1"}]
               [core/my-info! {}]
               [core/loan-history! {}]
               [core/reservation-status! {}]
               [core/interloan-status! {}]
               [core/hope-book-list! {}]
               [core/hope-book-detail! {:rec-key "12345"}]
               [core/basket-list! {}]]]
        (let [result (f client opts)]
          (is (= false (:ok? result)))
          (is (= :remote-error (:status result)))
          (is (= :http-request-failed (get-in result [:error :code]))))))))
