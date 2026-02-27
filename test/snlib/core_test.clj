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
