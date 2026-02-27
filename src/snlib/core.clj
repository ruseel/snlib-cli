(ns snlib.core
  (:require
   [clj-http.client :as http]
   [clj-http.cookies :as cookies]
   [clojure.string :as str])
  (:import
   (org.jsoup Jsoup)
   (org.jsoup.nodes Element)))

(def ^:private default-base-url "https://snlib.go.kr")
(def ^:private default-timeout-ms 20000)
(def ^:private default-user-agent "Mozilla/5.0 snlib-lib")

(def ^:private login-page-path "/intro/memberLogin.do")
(def ^:private login-submit-path "/intro/menu/10068/program/30025/memberLoginProc.do")
(def ^:private loan-status-path "/intro/menu/10060/program/30019/mypage/loanStatusList.do")
(def ^:private search-path "/intro/menu/10041/program/30009/plusSearchResultList.do")
(def ^:private default-hope-book-page-path "/intro/menu/10045/program/30011/hopeBookApply.do")
(def ^:private default-hope-book-submit-path "/intro/menu/10045/program/30011/hopeBookApplyProc.do")
(def ^:private interloan-popup-path "/intro/doorae/bandLillApplyPop.do")
(def ^:private interloan-submit-path "/intro/doorae/bandLillApplyPopProc.do")
(def ^:private default-return-url
  "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==")
(def ^:private interloan-success-token "상호대차 신청이 완료되었습니다.")

(defn create-client
  "Create a client atom with cookie-store for session reuse."
  ([] (create-client {}))
  ([{:keys [base-url timeout-ms user-agent]
     :or {base-url default-base-url
          timeout-ms default-timeout-ms
          user-agent default-user-agent}}]
   (atom {:base-url base-url
          :timeout-ms timeout-ms
          :user-agent user-agent
          :cookie-store (cookies/cookie-store)
          :last-login nil})))

(defn authenticated?
  [client]
  (true? (get-in @client [:last-login :ok?])))

(defn- error-envelope
  [status code message]
  {:ok? false
   :status status
   :data nil
   :error {:code code
           :message message}})

(defn- absolute-url
  [client path-or-url]
  (let [base-url (:base-url @client)
        path-or-url (or path-or-url "")]
    (cond
      (str/blank? path-or-url) base-url
      (str/starts-with? path-or-url "http") path-or-url
      :else (str base-url path-or-url))))

(defn- request
  [client {:keys [method url query-params form-params headers]
           :or {method :get headers {}}}]
  (let [{:keys [timeout-ms user-agent cookie-store]} @client
        base-opts {:throw-exceptions false
                   :as :text
                   :socket-timeout timeout-ms
                   :conn-timeout timeout-ms
                   :cookie-store cookie-store
                   :headers (merge {"User-Agent" user-agent}
                                   headers)}
        opts (cond-> base-opts
               query-params (assoc :query-params query-params)
               form-params (assoc :form-params form-params
                                  :content-type :x-www-form-urlencoded))]
    ((if (= method :post) http/post http/get)
     (absolute-url client url)
     opts)))

(defn- missing-input
  [{:keys [user-id password]}]
  (vec
   (concat
    (when (str/blank? (or user-id "")) [:user-id])
    (when (str/blank? (or password "")) [:password]))))

(defn- logged-out-page?
  [html]
  (let [body (or html "")]
    (boolean
     (or (str/includes? body "location.href='/intro/memberLogin.do'")
         (str/includes? body "로그인 후 이용가능합니다")
         (re-find #"(?is)<form[^>]+id=\"redirectForm\"[^>]*action=\"/intro/menu/10068/program/30025/memberLogin\.do\""
                  body)
         (re-find #"(?is)var\s+frm\s*=\s*document\.redirectForm.*?frm\.submit\(\)"
                  body)))))

(defn login!
  [client {:keys [user-id password return-url]}]
  (let [missing (missing-input {:user-id user-id :password password})]
    (if (seq missing)
      (error-envelope :invalid-input
                      :missing-required-input
                      (str "Missing required input: " (str/join ", " (map name missing))))
      (try
        (request client {:method :get :url login-page-path})
        (request client {:method :post
                         :url login-submit-path
                         :form-params {"returnUrl" (or return-url default-return-url)
                                       "userId" user-id
                                       "password" password}
                         :headers {"Referer" (absolute-url client login-page-path)}})
        (let [verify-res (request client {:method :get
                                          :url loan-status-path
                                          :headers {"Referer" (absolute-url client login-page-path)}})
              authenticated? (and (= 200 (:status verify-res))
                                  (not (logged-out-page? (:body verify-res))))
              status (if authenticated? :ok :requires-login)]
          (swap! client assoc :last-login {:ok? authenticated?
                                           :user-id user-id})
          {:ok? authenticated?
           :status status
           :data {:authenticated? authenticated?
                  :user-id user-id}
           :error nil})
        (catch Exception e
          (swap! client assoc :last-login {:ok? false
                                           :user-id user-id})
          (error-envelope :remote-error
                          :http-request-failed
                          (.getMessage e)))))))

(defn- parse-int-safe
  [v]
  (try
    (Integer/parseInt (str v))
    (catch Exception _ nil)))

(defn- normalize-library-codes
  [library-code]
  (->> (cond
         (nil? library-code) []
         (sequential? library-code) library-code
         :else [library-code])
       (map #(some-> % str str/trim))
       (remove str/blank?)
       vec))

(defn- build-search-query
  [{:keys [keyword library-code page per-page sort order]}]
  (let [library-codes (normalize-library-codes library-code)
        query {"searchType" "SIMPLE"
               "searchMenuCollectionCategory" ""
               "searchMenuEBookCategory" ""
               "searchCategory" "BOOK"
               "searchKey" "ALL"
               "searchKey1" ""
               "searchKey2" ""
               "searchKey3" ""
               "searchKey4" ""
               "searchKey5" ""
               "searchKeyword" keyword
               "searchKeyword1" ""
               "searchKeyword2" ""
               "searchKeyword3" ""
               "searchKeyword4" ""
               "searchKeyword5" ""
               "searchOperator1" ""
               "searchOperator2" ""
               "searchOperator3" ""
               "searchOperator4" ""
               "searchOperator5" ""
               "searchPublishStartYear" ""
               "searchPublishEndYear" ""
               "searchRoom" ""
               "searchKdc" ""
               "searchIsbn" ""
               "currentPageNo" (str (or page 1))
               "viewStatus" "IMAGE"
               "preSearchKey" "ALL"
               "preSearchKeyword" keyword
               "searchPbLibrary" "ALL"
               "searchSort" (or sort "SIMILAR")
               "searchOrder" (or order "DESC")
               "searchRecordCount" (str (or per-page 10))
               "searchBookClass" "ALL"}]
    (cond-> query
      (seq library-codes)
      (assoc "searchLibraryArr" (if (= 1 (count library-codes))
                                  (first library-codes)
                                  library-codes)))))

(defn- split-colon-value
  [s]
  (let [parts (str/split (or s "") #":" 2)]
    (when (= 2 (count parts))
      (some-> (second parts)
              (str/replace #",+$" "")
              str/trim
              not-empty))))

(defn- parse-author-meta
  [^Element item]
  (reduce
   (fn [acc ^Element span]
     (let [text (str/trim (.text span))
           value (split-colon-value text)]
       (cond
         (and value (str/starts-with? text "저자")) (assoc acc :author value)
         (and value (str/starts-with? text "발행자")) (assoc acc :publisher value)
         (and value (str/starts-with? text "발행연도")) (assoc acc :publish-year value)
         :else acc)))
   {:author nil
    :publisher nil
    :publish-year nil}
   (.select item "dd.author span")))

(defn- parse-manage-and-reg-no
  [^Element item]
  (let [onclick (->> (.select item "a[onclick]")
                     (map #(.attr ^Element % "onclick"))
                     (some #(when (str/includes? % "fnBandLillApplyPop")
                              %)))]
    (if-let [[_ manage-code reg-no]
             (and onclick
                  (re-find #"fnBandLillApplyPop\('([^']+)'\s*,\s*'([^']+)'\)"
                           onclick))]
      {:manage-code manage-code
       :reg-no reg-no}
      {:manage-code nil
       :reg-no nil})))

(defn- parse-search-items
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc "ul.resultList.imageType > li")
           (map (fn [^Element item]
                  (let [title (some-> (.select item "dt.tit a") first .text str/trim not-empty)
                        {:keys [author publisher publish-year]} (parse-author-meta item)
                        {:keys [manage-code reg-no]} (parse-manage-and-reg-no item)]
                    {:title (or title "")
                     :author (or author "")
                     :publisher (or publisher "")
                     :publish-year (or publish-year "")
                     :manage-code (or manage-code "")
                     :reg-no (or reg-no "")})))
           vec))))

(defn- parse-total-count
  [html]
  (if (str/blank? html)
    nil
    (let [doc (Jsoup/parse html)
          total-text (or (some-> (.select doc "p.rtitle strong.themeFC") first .text)
                         (some-> (.select doc ".resultListWrap .themeFC") first .text))
          digits (some-> total-text
                         (str/replace #"[^\d]" "")
                         not-empty)]
      (some-> digits parse-int-safe))))

(defn search-books!
  [client {:keys [keyword] :as opts}]
  (if (str/blank? (or keyword ""))
    (error-envelope :invalid-input
                    :missing-required-input
                    "Missing required input: keyword")
    (try
      (let [query-params (build-search-query opts)
            res (request client {:method :get
                                 :url search-path
                                 :query-params query-params
                                 :headers {"Referer" (absolute-url client "/intro/main/index.do")}})
            status-code (:status res)]
        (if (= 200 status-code)
          (let [items (parse-search-items (:body res))
                page (or (parse-int-safe (get query-params "currentPageNo")) 1)
                total-count (or (parse-total-count (:body res))
                                (count items))]
            {:ok? true
             :status :ok
             :data {:items items
                    :page page
                    :total-count total-count}
             :error nil})
          (error-envelope :remote-error
                          :search-request-failed
                          (str "Search request failed with status " status-code))))
      (catch Exception e
        (error-envelope :remote-error
                        :http-request-failed
                        (.getMessage e))))))

(def ^:private no-loan-rows-pattern
  #"(조회된\s*자료가\s*없습니다|대출\s*내역이\s*없습니다|검색\s*결과가\s*없습니다)")

(def ^:private returned-status-pattern
  #"(반납완료|반납\s*완료|반납됨|대출종료|회수완료)")

(defn- normalize-text
  [s]
  (some-> s
          str
          (str/replace #"\u00a0" " ")
          (str/replace #"\s+" " ")
          str/trim
          not-empty))

(defn- loan-header-key
  [header]
  (let [text (or header "")]
    (cond
      (re-find #"(서명|자료명|도서명|제목)" text) :title
      (re-find #"대출일" text) :loan-date
      (re-find #"(반납예정일|반납예정|예정반납일|반납일)" text) :due-date
      (re-find #"(대출상태|반납상태|상태)" text) :return-status
      (re-find #"(연장|재대출)" text) :renewable?
      :else nil)))

(defn- table-headers
  [^Element table]
  (let [thead-headers (->> (.select table "thead th")
                           (map #(.text ^Element %))
                           (map normalize-text)
                           (remove nil?)
                           vec)]
    (if (seq thead-headers)
      thead-headers
      (->> (.select table "tr:first-child th")
           (map #(.text ^Element %))
           (map normalize-text)
           (remove nil?)
           vec))))

(defn- loan-header-indexes
  [headers]
  (reduce
   (fn [acc [idx header]]
     (if-let [k (loan-header-key header)]
       (if (contains? acc k)
         acc
         (assoc acc k idx))
       acc))
   {}
   (map-indexed vector headers)))

(defn- parse-renewable?
  [value]
  (let [token (some-> value normalize-text (str/replace #"\s+" "") str/upper-case)]
    (cond
      (nil? token) nil
      (contains? #{"N" "NO" "FALSE" "X" "불가" "불가능" "연장불가"} token) false
      (contains? #{"Y" "YES" "TRUE" "O" "가능" "연장가능"} token) true
      (str/includes? token "불가") false
      (str/includes? token "가능") true
      :else nil)))

(defn- cell-at
  [cells idx]
  (when (and (some? idx)
             (< idx (count cells)))
    (nth cells idx)))

(defn- loan-from-row
  [cells header-indexes]
  (let [title (or (cell-at cells (:title header-indexes))
                  (cell-at cells 1)
                  (cell-at cells 0))
        loan-date (cell-at cells (:loan-date header-indexes))
        due-date (cell-at cells (:due-date header-indexes))
        return-status (cell-at cells (:return-status header-indexes))
        renewable? (parse-renewable? (cell-at cells (:renewable? header-indexes)))]
    (when (or title loan-date due-date return-status (some? renewable?))
      {:title title
       :loan-date loan-date
       :due-date due-date
       :return-status return-status
       :renewable? renewable?})))

(defn- parse-loans-from-table
  [^Element table]
  (let [header-indexes (loan-header-indexes (table-headers table))
        has-tbody? (pos? (.size (.select table "tbody")))
        row-selector (if has-tbody? "tbody tr" "tr")]
    (if (empty? header-indexes)
      []
      (->> (.select table row-selector)
           (filter #(pos? (.size (.select ^Element % "td"))))
           (map (fn [^Element tr]
                  (->> (.select tr "th, td")
                       (map #(.text ^Element %))
                       (map normalize-text)
                       (remove nil?)
                       vec)))
           (remove empty?)
           (remove #(and (= 1 (count %))
                         (re-find no-loan-rows-pattern (first %))))
           (map #(loan-from-row % header-indexes))
           (remove nil?)
           vec))))

(defn- parse-loan-status-loans
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc "table")
           (mapcat parse-loans-from-table)
           vec))))

(defn- returned-loan?
  [loan]
  (boolean (re-find returned-status-pattern
                    (or (:return-status loan) ""))))

(defn loan-status!
  [client {:keys [include-history?]}]
  (try
    (let [res (request client
                       {:method :get
                        :url loan-status-path
                        :headers {"Referer" (absolute-url client "/intro/main/index.do")}})
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {:loans []
                :count 0}
         :error nil}

        (= 200 status-code)
        (let [all-loans (parse-loan-status-loans html)
              loans (if include-history?
                      all-loans
                      (remove returned-loan? all-loans))
              loans' (vec loans)]
          {:ok? true
           :status :ok
           :data {:loans loans'
                  :count (count loans')}
           :error nil})

        :else
        (error-envelope :remote-error
                        :loan-status-request-failed
                        (str "Loan status request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

(defn- normalize-bool
  [v]
  (contains? #{"1" "true" "yes" "on"}
             (some-> v str str/trim str/lower-case)))

(def ^:private hope-book-field-aliases
  {"author" "author"
   "ea-isbn" "eaIsbn"
   "eaisbn" "eaIsbn"
   "email" "email"
   "hand-phone" "handPhone"
   "handphone" "handPhone"
   "manage-code" "manageCode"
   "managecode" "manageCode"
   "mobile-no1" "mobileNo1"
   "mobileno1" "mobileNo1"
   "mobile-no2" "mobileNo2"
   "mobileno2" "mobileNo2"
   "mobile-no3" "mobileNo3"
   "mobileno3" "mobileNo3"
   "price" "price"
   "publish-year" "publishYear"
   "publishyear" "publishYear"
   "publisher" "publisher"
   "recom-opinion" "recomOpinion"
   "recomopinion" "recomOpinion"
   "sms-receipt-yn" "smsReceiptYn"
   "smsreceiptyn" "smsReceiptYn"
   "title" "title"})

(defn- extract-forms
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc "form")
           (map (fn [^Element form]
                  {:id (or (.id form) "")
                   :name (.attr form "name")
                   :method (-> (or (.attr form "method") "GET")
                               str/trim
                               str/upper-case)
                   :action (some-> (.attr form "action")
                                   str/trim
                                   not-empty)
                   :fields (->> (.select form "input[name], select[name], textarea[name]")
                                (map (fn [^Element field]
                                       (let [tag (.tagName field)
                                             field-type (if (= "input" tag)
                                                          (.attr field "type")
                                                          tag)]
                                         {:name (.attr field "name")
                                          :type (or field-type "")
                                          :value (.attr field "value")})))
                                (remove #(str/blank? (:name %)))
                                vec)}))
           vec))))

(defn- form-hidden-defaults
  [form-spec]
  (->> (:fields form-spec)
       (filter #(= "hidden" (-> (or (:type %) "")
                                str/trim
                                str/lower-case)))
       (reduce (fn [acc {:keys [name value]}]
                 (assoc acc name (or value "")))
               {})))

(defn- hope-book-form?
  [form-spec]
  (let [field-names (->> (:fields form-spec)
                         (map :name)
                         set)]
    (or (= "registForm" (:name form-spec))
        (= "registForm" (:id form-spec))
        (and (contains? field-names "title")
             (contains? field-names "author")))))

(defn- select-hope-book-form
  [forms]
  (or (first (filter hope-book-form? forms))
      (first (filter #(= "POST" (:method %)) forms))
      (first forms)))

(defn- guess-hope-book-submit-path
  [html]
  (some-> (re-find #"/intro[^\s\"']*hopeBookApplyProc\.do" (or html ""))
          str/trim
          not-empty))

(defn- canonical-hope-book-key
  [k]
  (let [raw-key (cond
                  (keyword? k) (name k)
                  (string? k) k
                  :else (str k))
        alias-key (-> raw-key
                      str/trim
                      str/lower-case
                      (str/replace #"[_\s]+" "-"))]
    (or (get hope-book-field-aliases alias-key)
        raw-key)))

(defn- stringify-field-map
  [m]
  (reduce-kv (fn [acc k v]
               (assoc acc
                      (canonical-hope-book-key k)
                      (if (nil? v) "" (str v))))
             {}
             (if (map? m) m {})))

(defn- build-hope-book-payload
  [form-spec book-info applicant-info]
  (merge (form-hidden-defaults form-spec)
         (stringify-field-map book-info)
         (stringify-field-map applicant-info)))

(defn- hope-book-submit-allowed?
  [{:keys [allow-submit?]}]
  (let [write-ops-env (or (System/getenv "SNLIB_WRITE_OPS") "")]
    (or (true? allow-submit?)
        (normalize-bool (System/getenv "SNLIB_ALLOW_HOPE_BOOK_SUBMIT"))
        (normalize-bool (System/getenv "SNLIB_ALLOW_WRITE_SUBMITS"))
        (->> (str/split write-ops-env #",")
             (map str/trim)
             (remove str/blank?)
             (some #{"hope-book-submit"})
             boolean))))

(defn- parse-hope-book-result-message
  [html]
  (let [body (or html "")
        doc (when-not (str/blank? body) (Jsoup/parse body))
        message-from-alert (some->> (re-find #"(?is)alert\((?:'|\")(.+?)(?:'|\")\)" body)
                                    second
                                    str/trim
                                    not-empty)
        message-from-body (when doc
                            (some-> (.select doc ".messageBox p, .result p") first .text str/trim not-empty))
        message-from-title (when doc
                             (some-> (.select doc "title") first .text str/trim not-empty))]
    (or message-from-alert
        message-from-body
        message-from-title)))

(defn hope-book-request!
  [client
   {:keys [book-info applicant-info submit? page-path submit-path]
    :as opts}]
  (try
    (let [resolved-page-path (or page-path default-hope-book-page-path)
          page-res (request client
                            {:method :get
                             :url resolved-page-path
                             :headers {"Referer" (absolute-url client "/intro/main/index.do")}})
          page-html (:body page-res)
          page-logged-in? (and (= 200 (:status page-res))
                               (not (logged-out-page? page-html)))
          forms (extract-forms page-html)
          selected-form (select-hope-book-form forms)
          resolved-submit-path (or submit-path
                                   (:action selected-form)
                                   (guess-hope-book-submit-path page-html)
                                   default-hope-book-submit-path)
          prepared-payload (build-hope-book-payload selected-form book-info applicant-info)
          base-data {:prepared-payload prepared-payload
                     :submit-attempted? (boolean submit?)
                     :submit-blocked? false
                     :result-message nil}]
      (cond
        (not page-logged-in?)
        {:ok? false
         :status :requires-login
         :data (assoc base-data
                      :result-message "Login is required before submitting hope-book requests.")
         :error nil}

        (not submit?)
        {:ok? true
         :status :ok
         :data base-data
         :error nil}

        (not (hope-book-submit-allowed? opts))
        {:ok? false
         :status :blocked
         :data (assoc base-data
                      :submit-blocked? true
                      :result-message "Hope-book submit is blocked by default.")
         :error {:code :write-blocked
                 :message "Set :allow-submit? true or env SNLIB_ALLOW_HOPE_BOOK_SUBMIT=1 to submit."}}

        :else
        (let [submit-res (request client
                                  {:method :post
                                   :url resolved-submit-path
                                   :form-params prepared-payload
                                   :headers {"Referer" (absolute-url client resolved-page-path)}})
              status-code (:status submit-res)
              success? (<= 200 status-code 399)
              result-message (or (parse-hope-book-result-message (:body submit-res))
                                 (parse-hope-book-result-message page-html))
              status (if success? :ok :submit-failed)]
          {:ok? success?
           :status status
           :data (assoc base-data
                        :result-message result-message)
           :error (when-not success?
                    {:code :hope-book-submit-failed
                     :message (str "Hope-book submit failed with status " status-code)})})))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

(defn- interloan-submit-allowed?
  [{:keys [allow-submit?]}]
  (let [write-ops-env (or (System/getenv "SNLIB_WRITE_OPS") "")]
    (or (true? allow-submit?)
        (normalize-bool (System/getenv "SNLIB_ALLOW_INTERLOAN_SUBMIT"))
        (normalize-bool (System/getenv "SNLIB_ALLOW_WRITE_SUBMITS"))
        (->> (str/split write-ops-env #",")
             (map str/trim)
             (remove str/blank?)
             (some #{"interloan-submit"})
             boolean))))

(defn- missing-interloan-input
  [{:keys [manage-code reg-no give-lib-code apl-lib-code user-key submit?]}]
  (vec
   (concat
    (when (str/blank? (or manage-code "")) [:manage-code])
    (when (str/blank? (or reg-no "")) [:reg-no])
    (when submit?
      (concat
       (when (str/blank? (or give-lib-code "")) [:give-lib-code])
       (when (str/blank? (or apl-lib-code "")) [:apl-lib-code])
       (when (str/blank? (or user-key "")) [:user-key]))))))

(defn- build-interloan-payload
  [{:keys [manage-code reg-no give-lib-code apl-lib-code user-key appendix-apply-yn]}]
  {"manageCode" (or manage-code "")
   "regNo" (or reg-no "")
   "giveLibCode" (or give-lib-code "")
   "appendixApplyYn" (or appendix-apply-yn "N")
   "aplLibCode" (or apl-lib-code "")
   "userKey" (or user-key "")})

(defn- parse-interloan-result-message
  [html]
  (let [body (or html "")
        doc (when-not (str/blank? body) (Jsoup/parse body))
        message-from-body (when doc
                            (some-> (.select doc ".messageBox p") first .text str/trim not-empty))
        message-from-alert (some->> (re-find #"(?is)alert\((?:'|\")(.+?)(?:'|\")\)" body)
                                    second
                                    str/trim
                                    not-empty)]
    (or message-from-body
        message-from-alert
        (when (str/includes? body interloan-success-token)
          interloan-success-token))))

(defn interlibrary-loan-request!
  [client
   {:keys [manage-code reg-no submit?]
    :as opts}]
  (let [missing (missing-interloan-input opts)]
    (if (seq missing)
      (error-envelope :invalid-input
                      :missing-required-input
                      (str "Missing required input: " (str/join ", " (map name missing))))
      (try
        (let [prepared-payload (build-interloan-payload opts)
              popup-res (request client
                                 {:method :get
                                  :url interloan-popup-path
                                  :query-params {"manageCode" manage-code
                                                 "regNo" reg-no}
                                  :headers {"Referer" (absolute-url client "/intro/main/index.do")}})
              popup-logged-in? (and (= 200 (:status popup-res))
                                    (not (logged-out-page? (:body popup-res))))
              base-data {:prepared-payload prepared-payload
                         :submit-attempted? (boolean submit?)
                         :submit-blocked? false
                         :result-message nil}]
          (cond
            (not popup-logged-in?)
            {:ok? false
             :status :requires-login
             :data (assoc base-data
                          :result-message "Login is required before submitting interloan requests.")
             :error nil}

            (not submit?)
            {:ok? true
             :status :ok
             :data base-data
             :error nil}

            (not (interloan-submit-allowed? opts))
            {:ok? false
             :status :blocked
             :data (assoc base-data
                          :submit-blocked? true
                          :result-message "Interloan submit is blocked by default.")
             :error {:code :write-blocked
                     :message "Set :allow-submit? true or env SNLIB_ALLOW_INTERLOAN_SUBMIT=1 to submit."}}

            :else
            (let [submit-res (request client
                                      {:method :post
                                       :url interloan-submit-path
                                       :form-params prepared-payload
                                       :headers {"Origin" (:base-url @client)
                                                 "Referer" (str (absolute-url client interloan-popup-path)
                                                                "?manageCode=" manage-code
                                                                "&regNo=" reg-no)}})
                  status-code (:status submit-res)
                  result-message (parse-interloan-result-message (:body submit-res))
                  success? (and (= 200 status-code)
                                (str/includes? (or (:body submit-res) "") interloan-success-token))
                  status (if success? :ok :submit-failed)]
              {:ok? success?
               :status status
               :data (assoc base-data
                            :result-message result-message)
               :error (when-not success?
                        {:code :interloan-submit-failed
                         :message (str "Interloan submit failed with status " status-code)})})))
        (catch Exception e
          (error-envelope :remote-error
                          :http-request-failed
                          (.getMessage e)))))))

(defn interloan-request!
  [client opts]
  (interlibrary-loan-request! client opts))
