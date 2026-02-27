(ns snlib-cli.core-test
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [snlib-cli.core :as core]))

(defn- fixture
  [path]
  (-> (io/file path)
      slurp
      str/trim))

(deftest login-returns-map-test
  (testing "login result should be returned as a map"
    (is (= {:success? true
            :user-id "u100"
            :message "ok"}
           (core/login (fixture "data/fixtures/login_success.html"))))))

(deftest search-returns-map-test
  (testing "search result should be returned as a map with extracted items"
    (is (= {:total 2
            :items [{:isbn "978000000001"
                     :title "Clojure for CLI"
                     :author "Ruseel"
                     :available? true}
                    {:isbn "978000000002"
                     :title "Pragmatic Parsing"
                     :author "Amp"
                     :available? false}]}
           (core/search (fixture "data/fixtures/search_results.html"))))))

(deftest interlibrary-loan-request-guard-test
  (testing "interlibrary loan request submission must be blocked during dev/test"
    (is (= {:request-id "ill-001"
            :book-title "Distributed Clojure"
            :pickup-library "Main"
            :status "draft"
            :submission-allowed? false
            :blocked-reason "development/test safety guard"}
           (core/interlibrary-loan-request
             (fixture "data/fixtures/interlibrary_loan_draft.html"))))))

(deftest loan-status-returns-map-test
  (testing "loan status should be returned as a map"
    (is (= {:total 2
            :items [{:title "Data Driven Design"
                     :due-date "2026-03-20"
                     :status "on-loan"}
                    {:title "Testing with Mocks"
                     :due-date "2026-03-25"
                     :status "overdue"}]}
           (core/loan-status (fixture "data/fixtures/loan_status.html"))))))

(deftest wish-book-request-guard-test
  (testing "wish book submission must be blocked during dev/test"
    (is (= {:request-id "wish-101"
            :title "Reliable CLI"
            :author "Library Team"
            :status "draft"
            :submission-allowed? false
            :blocked-reason "development/test safety guard"}
           (core/wish-book-request (fixture "data/fixtures/wish_book_draft.html"))))))
