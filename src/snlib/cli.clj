(ns snlib.cli
  (:gen-class)
  (:require
   [clj-http.cookies :as cookies]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint :refer [pprint]]
   [clojure.string :as str]
   [clojure.tools.cli :refer [parse-opts]]
   [snlib.core :as core])
  (:import
   (java.util Date)
   (org.apache.http.impl.cookie BasicClientCookie)))

(def ^:private session-file-path
  (str (System/getProperty "user.home") "/.config/snlib-cli/session.edn"))

(def ^:private credentials-file-path
  (str (System/getProperty "user.home") "/.config/snlib-cli/credentials.edn"))

(defn- cookie->data
  [cookie]
  {:name (.getName cookie)
   :value (.getValue cookie)
   :domain (.getDomain cookie)
   :path (.getPath cookie)
   :secure? (.isSecure cookie)
   :expiry-ms (some-> (.getExpiryDate cookie) .getTime)})

(defn- data->cookie
  [{:keys [name value domain path secure? expiry-ms]}]
  (when-not (str/blank? (or name ""))
    (let [cookie (BasicClientCookie. name (or value ""))]
      (when-not (str/blank? (or domain ""))
        (.setDomain cookie domain))
      (when-not (str/blank? (or path ""))
        (.setPath cookie path))
      (.setSecure cookie (boolean secure?))
      (when (some? expiry-ms)
        (.setExpiryDate cookie (Date. expiry-ms)))
      cookie)))

(defn- load-cookie-store
  []
  (let [store (cookies/cookie-store)
        session-file (io/file session-file-path)]
    (if-not (.exists session-file)
      store
      (try
        (let [session-data (edn/read-string (slurp session-file))
              cookies-data (if (map? session-data)
                             (:cookies session-data)
                             nil)]
          (doseq [cookie-data (or cookies-data [])
                  :let [cookie (data->cookie cookie-data)]
                  :when cookie]
            (.addCookie store cookie))
          store)
        (catch Exception _
          store)))))

(defn- save-cookie-store!
  [store]
  (let [session-file (io/file session-file-path)
        parent-dir (.getParentFile session-file)
        cookies-data (mapv cookie->data (.getCookies store))
        payload {:saved-at-ms (System/currentTimeMillis)
                 :cookies cookies-data}]
    (when parent-dir
      (.mkdirs parent-dir))
    (spit session-file (pr-str payload))))

(defn- load-credentials
  []
  (let [credentials-file (io/file credentials-file-path)]
    (if-not (.exists credentials-file)
      {}
      (try
        (let [data (edn/read-string (slurp credentials-file))]
          (if (map? data)
            data
            {}))
        (catch Exception _
          {})))))

(defn- save-credentials!
  [{:keys [user-id password]}]
  (let [credentials-file (io/file credentials-file-path)
        parent-dir (.getParentFile credentials-file)
        payload {:user-id user-id
                 :password password
                 :saved-at-ms (System/currentTimeMillis)}]
    (when parent-dir
      (.mkdirs parent-dir))
    (spit credentials-file (pr-str payload))))

(def ^:private commands
  {"login" core/login!
   "search-books" core/search-books!
   "loan-status" core/loan-status!
   "hope-book-request" core/hope-book-request!
   "interlibrary-loan-request" core/interlibrary-loan-request!
   "interloan-request" core/interloan-request!})

(defn- parse-int
  [label s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException _
      (throw (ex-info (str "Expected integer for " label ": " s)
                      {:label label
                       :value s})))))

(defn- parse-kv
  [label s]
  (let [[k v] (str/split (or s "") #"=" 2)]
    (if (and (not (str/blank? k))
             (some? v))
      [(keyword (str/trim k)) (str/trim v)]
      (throw (ex-info (str "Expected KEY=VALUE for " label ": " s)
                      {:label label
                       :value s})))))

(def ^:private cli-options
  [[nil "--base-url URL" "SNLib base URL"]
   [nil "--timeout-ms MS" "Request timeout (ms)"
    :parse-fn #(parse-int "--timeout-ms" %)]
   [nil "--user-agent USER_AGENT" "HTTP User-Agent"]
   [nil "--user-id USER_ID" "Login user ID"]
   [nil "--password PASSWORD" "Login password"]
   [nil "--return-url RETURN_URL" "Encoded login return URL"]
   [nil "--keyword KEYWORD" "Book search keyword"]
   [nil "--library-code CODE" "Library code (repeatable)"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--page PAGE" "Search page"
    :parse-fn #(parse-int "--page" %)]
   [nil "--per-page N" "Items per page"
    :parse-fn #(parse-int "--per-page" %)]
   [nil "--sort SORT" "Search sort key (default: SIMILAR)"]
   [nil "--order ORDER" "Search order (default: DESC)"]
   [nil "--include-history" "Include returned loans in loan-status"]
   [nil "--submit" "Perform submit action (otherwise dry run)"]
   [nil "--allow-submit" "Allow write submit from CLI"]
   [nil "--page-path PATH" "Override hope-book page path"]
   [nil "--submit-path PATH" "Override submit path"]
   [nil "--manage-code CODE" "Interloan manage code"]
   [nil "--reg-no REG_NO" "Interloan registration number"]
   [nil "--apl-lib-code CODE" "Applicant library code"]
   [nil "--give-lib-code CODE" "Giving library code"]
   [nil "--user-key USER_KEY" "Interloan user key"]
   [nil "--appendix-apply-yn YN" "Interloan appendix apply flag"]
   [nil "--book-info KEY=VALUE" "Hope-book book field (repeatable)"
    :parse-fn #(parse-kv "--book-info" %)
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--applicant-info KEY=VALUE" "Hope-book applicant field (repeatable)"
    :parse-fn #(parse-kv "--applicant-info" %)
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--pretty" "Pretty-print EDN output"]
   [nil "--help" "Show help"]])

(defn- usage
  [summary]
  (str "snlib CLI\n\n"
       "Usage:\n"
       "  snlib <command> [options]\n\n"
       "Commands (verbs):\n"
       "  login\n"
       "  search-books\n"
       "  loan-status\n"
       "  hope-book-request\n"
       "  interlibrary-loan-request\n"
       "  interloan-request\n\n"
       "Long options:\n"
       summary "\n\n"
       "Examples:\n"
       "  snlib login --user-id myid --password secret\n"
       "  snlib search-books --keyword franklin --library-code MA --page 1 --per-page 10\n"
       "  snlib loan-status --include-history\n"
       "  snlib interloan-request --manage-code MA --reg-no CEM000050087 --submit --apl-lib-code MB"))

(defn- command-opts
  [command opts]
  (case command
    "login"
    (let [stored (load-credentials)]
      {:user-id (or (:user-id opts) (:user-id stored))
       :password (or (:password opts) (:password stored))
       :return-url (:return-url opts)})

    "search-books"
    (select-keys opts [:keyword :library-code :page :per-page :sort :order])

    "loan-status"
    (select-keys opts [:include-history?])

    "hope-book-request"
    (-> (select-keys opts [:submit? :allow-submit? :page-path :submit-path])
        (assoc :book-info (into {} (:book-info opts))
               :applicant-info (into {} (:applicant-info opts))))

    "interlibrary-loan-request"
    (select-keys opts [:manage-code :reg-no :submit? :allow-submit?
                       :apl-lib-code :give-lib-code :user-key :appendix-apply-yn])

    "interloan-request"
    (select-keys opts [:manage-code :reg-no :submit? :allow-submit?
                       :apl-lib-code :give-lib-code :user-key :appendix-apply-yn])

    {}))

(defn- print-result!
  [result pretty?]
  (if pretty?
    (pprint result)
    (prn result)))

(defn run-cli
  [args]
  (let [[command & raw-opts] args
        {:keys [options arguments errors summary]} (parse-opts raw-opts cli-options)
        help-text (usage summary)]
    (cond
      (or (nil? command)
          (= command "--help")
          (= command "help")
          (:help options))
      {:exit-code 0
       :help-text help-text}

      (str/starts-with? command "-")
      {:exit-code 1
       :error-text (str "Missing command.\n\n" help-text)}

      (not (contains? commands command))
      {:exit-code 1
       :error-text (str "Unknown command: " command "\n\n" help-text)}

      (seq arguments)
      {:exit-code 1
       :error-text (str "Unexpected positional arguments: " (str/join " " arguments) "\n\n" help-text)}

      (seq errors)
      {:exit-code 1
       :error-text (str (str/join "\n" errors) "\n\n" help-text)}

      :else
      (let [cookie-store (load-cookie-store)
            client (core/create-client (assoc (select-keys options [:base-url :timeout-ms :user-agent])
                                              :cookie-store cookie-store))]
        (try
          (let [resolved-opts (command-opts command options)
                result ((get commands command) client resolved-opts)]
            (when (and (= command "login")
                       (:ok? result)
                       (not (str/blank? (or (:user-id resolved-opts) "")))
                       (not (str/blank? (or (:password resolved-opts) ""))))
              (save-credentials! resolved-opts))
            {:exit-code (if (:ok? result) 0 2)
             :result result
             :pretty? (:pretty options)})
          (finally
            (save-cookie-store! cookie-store)))))))

(defn -main
  [& args]
  (let [{:keys [exit-code help-text error-text result pretty?]} (run-cli args)]
    (cond
      help-text
      (println help-text)

      error-text
      (binding [*out* *err*]
        (println error-text))

      result
      (print-result! result pretty?))
    (System/exit exit-code)))
