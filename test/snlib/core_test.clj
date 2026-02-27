(ns snlib.core-test
  (:require
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
