(ns snlib.cli-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [snlib.cli :as cli]
   [snlib.core :as core]))

(defn- cli-var
  [sym]
  (or (ns-resolve 'snlib.cli sym)
      (throw (ex-info "Missing private var" {:symbol sym}))))

(defn- with-cli-redefs
  [overrides f]
  (let [defaults {(cli-var 'commands) {}
                  (cli-var 'load-session-data) (fn [] {})
                  (cli-var 'load-cookie-store) (fn [_] :cookie-store)
                  (cli-var 'save-session!) (fn [_ _] nil)
                  (cli-var 'load-env-credentials) (fn [] {})
                  (cli-var 'load-credentials) (fn [] {})
                  (cli-var 'save-credentials!) (fn [_] nil)
                  #'core/create-client (fn [_opts] :client)}]
    (with-redefs-fn (merge defaults overrides)
      f)))

(deftest run-cli-maps-include-history-flag-for-loan-status
  (let [captured-opts (atom nil)]
    (with-cli-redefs
      {(cli-var 'commands)
       {"loan-status" (fn [_client opts]
                        (reset! captured-opts opts)
                        {:ok? true :status :ok :data {} :error nil})}}
      #(let [result (cli/run-cli ["loan-status" "--include-history"])]
         (is (= 0 (:exit-code result)))
         (is (= {:include-history? true} @captured-opts))))))

(deftest run-cli-maps-request-edn-for-hope-book-request
  (let [captured-opts (atom nil)]
    (with-cli-redefs
      {(cli-var 'commands)
       {"hope-book-request" (fn [_client opts]
                              (reset! captured-opts opts)
                              {:ok? true :status :ok :data {} :error nil})}}
      #(let [result (cli/run-cli ["hope-book-request"
                                  "--manage-code" "MU"
                                  "--request-edn" "{:title \"테스트\" :author \"홍길동\" :handPhone \"010-1234-5678\"}"
                                  "--submit"
                                  "--allow-submit"])]
         (is (= 0 (:exit-code result)))
         (is (= {:manage-code "MU"
                 :submit? true
                 :allow-submit? true
                 :request {:title "테스트"
                           :author "홍길동"
                           :handPhone "010-1234-5678"}}
                @captured-opts))))))

(deftest run-cli-saves-credentials-only-when-explicitly-enabled
  (let [saved (atom [])]
    (with-cli-redefs
      {(cli-var 'commands)
       {"login" (fn [_client _opts]
                  {:ok? true :status :ok :data {} :error nil})}
       (cli-var 'save-credentials!)
       (fn [credentials]
         (swap! saved conj credentials))}
      #(do
         (cli/run-cli ["login" "--user-id" "alice" "--password" "secret"])
         (cli/run-cli ["login" "--user-id" "bob" "--password" "secret" "--save-credentials"])))
    (is (= 1 (count @saved)))
    (is (= "bob" (get-in @saved [0 :user-id])))))

(deftest run-cli-uses-login-credentials-in-priority-order
  (let [captured-opts (atom nil)]
    (with-cli-redefs
      {(cli-var 'load-env-credentials) (constantly {:user-id "env-user"
                                                    :password "env-pass"})
       (cli-var 'load-credentials) (constantly {:user-id "file-user"
                                                :password "file-pass"})
       (cli-var 'commands)
       {"login" (fn [_client opts]
                  (reset! captured-opts opts)
                  {:ok? true :status :ok :data {} :error nil})}}
      #(let [result (cli/run-cli ["login" "--user-id" "cli-user" "--password" "cli-pass"])]
         (is (= 0 (:exit-code result)))
         (is (= {:user-id "cli-user"
                 :password "cli-pass"
                 :return-url nil}
                @captured-opts))))))

(deftest run-cli-uses-env-credentials-before-saved-file
  (let [captured-opts (atom nil)]
    (with-cli-redefs
      {(cli-var 'load-env-credentials) (constantly {:user-id "env-user"
                                                    :password "env-pass"})
       (cli-var 'load-credentials) (constantly {:user-id "file-user"
                                                :password "file-pass"})
       (cli-var 'commands)
       {"login" (fn [_client opts]
                  (reset! captured-opts opts)
                  {:ok? true :status :ok :data {} :error nil})}}
      #(let [result (cli/run-cli ["login"])]
         (is (= 0 (:exit-code result)))
         (is (= {:user-id "env-user"
                 :password "env-pass"
                 :return-url nil}
                @captured-opts))))))

(deftest run-cli-falls-back-to-saved-credentials-when-env-missing
  (let [captured-opts (atom nil)]
    (with-cli-redefs
      {(cli-var 'load-env-credentials) (constantly {:user-id "" :password nil})
       (cli-var 'load-credentials) (constantly {:user-id "file-user"
                                                :password "file-pass"})
       (cli-var 'commands)
       {"login" (fn [_client opts]
                  (reset! captured-opts opts)
                  {:ok? true :status :ok :data {} :error nil})}}
      #(let [result (cli/run-cli ["login"])]
         (is (= 0 (:exit-code result)))
         (is (= {:user-id "file-user"
                 :password "file-pass"
                 :return-url nil}
                @captured-opts))))))

(deftest run-cli-returns-error-for-unknown-command
  (let [result (cli/run-cli ["unknown-cmd"])]
    (is (= 1 (:exit-code result)))
    (is (str/includes? (:error-text result) "Unknown command: unknown-cmd"))))

(deftest run-cli-uses-exit-code-2-for-command-level-failure
  (with-cli-redefs
    {(cli-var 'commands)
     {"my-info" (fn [_client _opts]
                  {:ok? false
                   :status :requires-login
                   :data {}
                   :error nil})}}
    #(let [result (cli/run-cli ["my-info"])]
       (is (= 2 (:exit-code result)))
       (is (= :requires-login (get-in result [:result :status]))))))

(deftest run-cli-blocks-login-required-command-when-session-expired
  (let [now 50000000
        ttl (* 3 60 60 1000)
        login-at (- now ttl 1)]
    (with-cli-redefs
      {(cli-var 'now-ms) (constantly now)
       (cli-var 'load-session-data) (constantly {:last-login-at-ms login-at})
       (cli-var 'commands) {"loan-status" (fn [& _]
                                            (throw (ex-info "should not execute" {})))}
       (cli-var 'command-opts) (fn [_ _] {})}
      #(let [result (cli/run-cli ["loan-status"])]
         (is (= 2 (:exit-code result)))
         (is (= :requires-login (get-in result [:result :status])))
         (is (= :login-session-expired
                (get-in result [:result :error :code])))))))

(deftest run-cli-updates-last-login-time-on-successful-login
  (let [now 1234567
        saved-session (atom nil)]
    (with-cli-redefs
      {(cli-var 'now-ms) (constantly now)
       (cli-var 'load-session-data) (constantly {:last-login-at-ms 10})
       (cli-var 'save-session!) (fn [_ payload]
                                  (reset! saved-session payload))
       (cli-var 'commands) {"login" (fn [_ _]
                              {:ok? true
                               :status :ok
                               :data {:authenticated? true}
                               :error nil})}}
      #(let [result (cli/run-cli ["login" "--user-id" "alice" "--password" "secret"])]
         (is (= 0 (:exit-code result)))
         (is (= {:last-login-at-ms now}
                @saved-session))))))

(deftest run-cli-allows-login-exempt-command-even-when-session-expired
  (let [now 80000000
        ttl (* 3 60 60 1000)
        login-at (- now ttl 10)]
    (with-cli-redefs
      {(cli-var 'now-ms) (constantly now)
       (cli-var 'load-session-data) (constantly {:last-login-at-ms login-at})
       (cli-var 'commands) {"search-books" (fn [_ _]
                                             {:ok? true
                                              :status :ok
                                              :data {:items []}
                                              :error nil})}
       (cli-var 'command-opts) (fn [_ _] {:keyword "abc"})}
      #(let [result (cli/run-cli ["search-books" "--keyword" "abc"])]
         (is (= 0 (:exit-code result)))
         (is (= :ok (get-in result [:result :status])))))))
