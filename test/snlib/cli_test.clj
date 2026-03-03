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
                  (cli-var 'load-cookie-store) (fn [] :cookie-store)
                  (cli-var 'save-cookie-store!) (fn [_] nil)
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
