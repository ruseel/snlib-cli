(ns snlib.cli
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
   (org.apache.http.impl.cookie BasicClientCookie))
  (:gen-class))

(def ^:private session-file-path
  (str (System/getProperty "user.home") "/.config/snlib-cli/session.edn"))

(def ^:private credentials-file-path
  (str (System/getProperty "user.home") "/.config/snlib-cli/credentials.edn"))

(def ^:private login-session-ttl-ms
  (* 3 60 60 1000))

(def ^:private default-cli-base-url "https://snlib.go.kr")
(def ^:private default-cli-timeout-ms 20000)
(def ^:private default-cli-user-agent "Mozilla/5.0 snlib-lib")
(def ^:private default-login-return-url "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==")
(def ^:private default-hope-book-page-path "/intro/menu/10045/program/30011/hopeBookApply.do")
(def ^:private default-hope-book-submit-path "/intro/menu/10045/program/30011/hopeBookApplyProc.do")

(defn- now-ms
  []
  (System/currentTimeMillis))

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

(defn- load-session-data
  []
  (let [session-file (io/file session-file-path)]
    (if-not (.exists session-file)
      {}
      (try
        (let [session-data (edn/read-string (slurp session-file))]
          (if (map? session-data)
            session-data
            {}))
        (catch Exception _
          {})))))

(defn- load-cookie-store
  [session-data]
  (let [store (cookies/cookie-store)]
    (doseq [cookie-data (or (:cookies session-data) [])
            :let [cookie (data->cookie cookie-data)]
            :when cookie]
      (.addCookie store cookie))
    store))

(defn- save-session!
  [store {:keys [last-login-at-ms]}]
  (let [session-file (io/file session-file-path)
        parent-dir (.getParentFile session-file)
        cookies-data (mapv cookie->data (.getCookies store))
        payload {:saved-at-ms (now-ms)
                 :last-login-at-ms last-login-at-ms
                 :cookies cookies-data}]
    (when parent-dir
      (.mkdirs parent-dir))
    (spit session-file (pr-str payload))
    (.setReadable session-file false false)
    (.setWritable session-file false false)
    (.setExecutable session-file false false)
    (.setReadable session-file true true)
    (.setWritable session-file true true)))

(defn- login-session-expired?
  [last-login-at-ms]
  (and (number? last-login-at-ms)
       (>= (- (now-ms) last-login-at-ms) login-session-ttl-ms)))

(def ^:private login-exempt-commands
  #{"login" "search-books"})

(defn- requires-login-command?
  [command]
  (not (contains? login-exempt-commands command)))

(defn- expired-login-result
  []
  {:ok? false
   :status :requires-login
   :data nil
   :error {:code :login-session-expired
           :message "Login session expired after 3 hours. Please run `snlib login` again."}})

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
    (spit credentials-file (pr-str payload))
    (.setReadable credentials-file false false)
    (.setWritable credentials-file false false)
    (.setExecutable credentials-file false false)
    (.setReadable credentials-file true true)
    (.setWritable credentials-file true true)))

(def ^:private commands
  {"login" core/login!
   "search-books" core/search-books!
   "loan-status" core/loan-status!
   "hope-book-request" core/hope-book-request!
   "interlibrary-loan-request" core/interlibrary-loan-request!
   "interloan-request" core/interloan-request!
   "my-info" core/my-info!
   "loan-history" core/loan-history!
   "reservation-status" core/reservation-status!
   "interloan-status" core/interloan-status!
   "hope-book-list" core/hope-book-list!
   "hope-book-detail" core/hope-book-detail!
   "basket-list" core/basket-list!})

(defn- parse-int
  [label s]
  (try
    (Integer/parseInt s)
    (catch NumberFormatException _
      (throw (ex-info (str "Expected integer for " label ": " s)
                      {:label label
                       :value s})))))

(defn- parse-edn-map
  [label s]
  (try
    (let [parsed (edn/read-string s)]
      (if (map? parsed)
        parsed
        (throw (ex-info (str "Expected EDN map for " label ": " s)
                        {:label label
                         :value s}))))
    (catch clojure.lang.ExceptionInfo e
      (throw e))
    (catch Exception e
      (throw (ex-info (str "Failed to parse EDN for " label ": " s)
                      {:label label
                       :value s}
                      e)))))

(def ^:private cli-options
  [[nil "--base-url URL" (str "SNLib base URL (default: " default-cli-base-url ")")]
   [nil "--timeout-ms MS" (str "Request timeout (ms, default: " default-cli-timeout-ms ")")
    :parse-fn #(parse-int "--timeout-ms" %)]
   [nil "--user-agent USER_AGENT" (str "HTTP User-Agent (default: " default-cli-user-agent ")")]
   [nil "--user-id USER_ID" "Login user ID"]
   [nil "--password PASSWORD" "Login password"]
   [nil "--return-url RETURN_URL" (str "Encoded login return URL (default: " default-login-return-url ")")]
   [nil "--keyword KEYWORD" "Book search keyword"]
   [nil "--manage-code CODE" "Manage code (for search-books/interloan, ex: MA, MB, MS)"]
   [nil "--library-code CODE" "[Deprecated] Alias of --manage-code for search-books"
    :assoc-fn (fn [m k v] (update m k (fnil conj []) v))]
   [nil "--page PAGE" "Search page (default: 1)"
    :parse-fn #(parse-int "--page" %)]
   [nil "--per-page N" "Items per page (default: 10)"
    :parse-fn #(parse-int "--per-page" %)]
   [nil "--sort SORT" "Search sort key (default: SIMILAR)"]
   [nil "--order ORDER" "Search order (default: DESC)"]
   [nil "--include-history" "Include returned loans in loan-status (default: false)"]
   [nil "--save-credentials" "Persist login credentials locally (disabled by default)"]
   [nil "--submit" "Perform submit action (default: false, otherwise dry run)"]
   [nil "--allow-submit" "Allow write submit from CLI (default: false)"]
   [nil "--page-path PATH" (str "Override hope-book page path (default: " default-hope-book-page-path ")")]
   [nil "--submit-path PATH" (str "Override submit path (default fallback: " default-hope-book-submit-path ")")]
   [nil "--reg-no REG_NO" "Interloan registration number"]
   [nil "--apl-lib-code CODE" "Applicant library code (6-digit numeric, see data/lib-code.edn)"]
   [nil "--give-lib-code CODE" "Giving library code (6-digit numeric, usually auto-filled from popup)"]
   [nil "--user-key USER_KEY" "Interloan user key"]
   [nil "--appendix-apply-yn YN" "Interloan appendix apply flag (default: N)"]
   [nil "--request-edn EDN" "Hope-book request payload as a single EDN map"
    :parse-fn #(parse-edn-map "--request-edn" %)]
   [nil "--rec-key KEY" "Hope-book detail rec-key"]
   [nil "--group-key KEY" "Basket group key (default: first parsed basket group)"]
   [nil "--pretty" "Pretty-print EDN output"]
   [nil "--help" "Show help"]])

(def ^:private command-help
  {"login"
   ["--user-id USER_ID (required unless saved credentials exist)"
    "--password PASSWORD (required unless saved credentials exist)"
    (str "--return-url RETURN_URL (default: " default-login-return-url ")")
    "--save-credentials (default: false)"]

   "search-books"
   ["--keyword KEYWORD (required)"
    "--manage-code CODE (optional, repeatable)"
    "--library-code CODE ([Deprecated], alias of --manage-code)"
    "--page PAGE (default: 1)"
    "--per-page N (default: 10)"
    "--sort SORT (default: SIMILAR)"
    "--order ORDER (default: DESC)"]

   "loan-status"
   ["--include-history (default: false)"]

   "hope-book-request"
   ["--request-edn EDN (optional, single map)"
    "--submit (default: false)"
    "--allow-submit (default: false)"
    (str "--page-path PATH (default: " default-hope-book-page-path ")")
    (str "--submit-path PATH (default fallback: " default-hope-book-submit-path ")")]

   "interlibrary-loan-request"
   ["--manage-code CODE (required)"
    "--reg-no REG_NO (required)"
    "--apl-lib-code CODE (required when --submit)"
    "--give-lib-code CODE (optional, usually auto-filled)"
    "--user-key USER_KEY (optional, usually auto-filled)"
    "--appendix-apply-yn YN (default: N)"
    "--submit (default: false)"
    "--allow-submit (default: false)"]

   "interloan-request"
   ["Same options as interlibrary-loan-request"]

   "my-info"
   ["(no command-specific options)"]

   "loan-history"
   ["(no command-specific options)"]

   "reservation-status"
   ["(no command-specific options)"]

   "interloan-status"
   ["(no command-specific options)"]

   "hope-book-list"
   ["(no command-specific options)"]

   "hope-book-detail"
   ["--rec-key KEY (required)"]

   "basket-list"
   ["--group-key KEY (optional; default: first parsed basket group)"]})

(def ^:private command-order
  ["login"
   "search-books"
   "loan-status"
   "hope-book-request"
   "interlibrary-loan-request"
   "interloan-request"
   "my-info"
   "loan-history"
   "reservation-status"
   "interloan-status"
   "hope-book-list"
   "hope-book-detail"
   "basket-list"])

(defn- command-help-section
  []
  (str/join
    "\n"
    (mapcat
      (fn [command]
        (let [lines (get command-help command [])]
          (concat [(str "  " command)]
                  (map #(str "    " %) lines)
                  [""])))
      command-order)))

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
       "  interloan-request\n"
       "  my-info\n"
       "  loan-history\n"
       "  reservation-status\n"
       "  interloan-status\n"
       "  hope-book-list\n"
       "  hope-book-detail\n"
       "  basket-list\n\n"
       "Command options:\n"
       (command-help-section) "\n"
       "Long options:\n"
       summary "\n\n"
       "Examples:\n"
       "  snlib login --user-id myid --password secret\n"
       "  snlib search-books --keyword franklin --manage-code MA --page 1 --per-page 10\n"
       "  snlib loan-status --include-history\n"
       "  snlib interloan-request --manage-code MA --reg-no CEM000050087 --submit --apl-lib-code 141484\n"
       "  snlib hope-book-request --request-edn '{:title \"도서명\" :author \"저자\" :handPhone \"010-1234-5678\" :email \"user@example.com\"}' --submit --allow-submit\n"
       "  snlib my-info\n"
       "  snlib hope-book-detail --rec-key 1938103961\n"
       "  snlib basket-list --group-key 13840"))

(defn- first-manage-code
  [v]
  (cond
    (sequential? v) (some->> v (remove str/blank?) first)
    :else v))

(defn- command-opts
  [command opts]
  (case command
    "login"
    (let [stored (load-credentials)]
      {:user-id (or (:user-id opts) (:user-id stored))
       :password (or (:password opts) (:password stored))
       :return-url (:return-url opts)})

    "search-books"
    (-> (select-keys opts [:keyword :manage-code :library-code :page :per-page :sort :order])
        ;; Backward compatibility: --library-code still works, but normalize to :manage-code.
        (update :manage-code #(or % (:library-code opts)))
        (dissoc :library-code))

    "loan-status"
    {:include-history? (boolean (:include-history opts))}

    "hope-book-request"
    (-> (select-keys opts [:page-path :submit-path])
        (assoc :submit? (boolean (:submit opts))
               :allow-submit? (boolean (:allow-submit opts))
               :request (or (:request-edn opts) {})))

    "interlibrary-loan-request"
    (-> (select-keys opts [:manage-code :reg-no :apl-lib-code :give-lib-code :user-key :appendix-apply-yn])
        ;; If manage-code is provided multiple times, interloan uses the first one.
        (update :manage-code first-manage-code)
        (assoc :submit? (boolean (:submit opts))
               :allow-submit? (boolean (:allow-submit opts))))

    "interloan-request"
    (-> (select-keys opts [:manage-code :reg-no :apl-lib-code :give-lib-code :user-key :appendix-apply-yn])
        ;; If manage-code is provided multiple times, interloan uses the first one.
        (update :manage-code first-manage-code)
        (assoc :submit? (boolean (:submit opts))
               :allow-submit? (boolean (:allow-submit opts))))

    "my-info" {}

    "loan-history" {}

    "reservation-status" {}

    "interloan-status" {}

    "hope-book-list" {}

    "hope-book-detail"
    (select-keys opts [:rec-key])

    "basket-list"
    (select-keys opts [:group-key])

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
      (let [session-data (load-session-data)
            last-login-at-ms (:last-login-at-ms session-data)
            cookie-store (load-cookie-store session-data)
            client (core/create-client (assoc (select-keys options [:base-url :timeout-ms :user-agent])
                                              :cookie-store cookie-store))
            resolved-opts (command-opts command options)
            now (now-ms)
            result (if (and (requires-login-command? command)
                            (login-session-expired? last-login-at-ms))
                     (expired-login-result)
                     ((get commands command) client resolved-opts))
            next-last-login-at-ms (if (and (= command "login")
                                           (:ok? result))
                                    now
                                    last-login-at-ms)]
        (try
          (when (and (= command "login")
                     (:ok? result)
                     (:save-credentials options)
                     (not (str/blank? (or (:user-id resolved-opts) "")))
                     (not (str/blank? (or (:password resolved-opts) ""))))
            (save-credentials! resolved-opts))
          {:exit-code (if (:ok? result) 0 2)
           :result result
           :pretty? (:pretty options)}
          (finally
            (save-session! cookie-store {:last-login-at-ms next-last-login-at-ms})))))))

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
