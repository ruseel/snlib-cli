(ns snlib.core
  "Core command handlers for SNLib.

  Each public `*!` function corresponds to a single CLI command and accepts
  only that command's `opts` map."
  (:require
   [clj-http.client :as http]
   [clj-http.cookies :as cookies]
   [clojure.string :as str]
   [snlib.codes :as codes])
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
(def ^:private interloan-success-token "상호대차 신청이 완료되었습니다.")

(def ^:private my-info-path "/intro/menu/10055/program/30017/mypage/myInfo.do")
(def ^:private reservation-status-path "/intro/menu/10061/program/30020/mypage/reservationStatusList.do")
(def ^:private loan-history-path "/intro/menu/10062/program/30021/mypage/loanHistoryList.do")
(def ^:private interloan-status-path "/intro/bandLillStatusList.do")
(def ^:private hope-book-list-path "/intro/menu/10065/program/30011/mypage/hopeBookList.do")
(def ^:private hope-book-detail-path "/intro/menu/10065/program/30011/mypage/hopeBookDetail.do")
(def ^:private basket-group-main-path "/intro/menu/10057/program/30018/mypage/basketGroupMain.do")
(def ^:private basket-group-book-list-path "/intro/menu/10057/program/30018/mypage/basketGroupBookList.do")

(defn create-client
  "Create a client atom with cookie-store for session reuse.

  opts (optional):
  - :base-url (default: \"https://snlib.go.kr\")
  - :timeout-ms (default: 20000)
  - :user-agent (default: \"Mozilla/5.0 snlib-lib\")
  - :cookie-store (default: fresh in-memory cookie-store)"
  ([] (create-client {}))
  ([{:keys [base-url timeout-ms user-agent cookie-store]
     :or {base-url default-base-url
          timeout-ms default-timeout-ms
          user-agent default-user-agent
          cookie-store (cookies/cookie-store)}}]
   (atom {:base-url base-url
          :timeout-ms timeout-ms
          :user-agent user-agent
          :cookie-store cookie-store
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

(defn- default-request-opts
  [client]
  (let [{:keys [timeout-ms user-agent cookie-store]} @client]
    {:throw-exceptions false
     :as :text
     :socket-timeout timeout-ms
     :conn-timeout timeout-ms
     :cookie-store cookie-store
     :headers {"User-Agent" user-agent}}))

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
  "Run `login` command.

  opts:
  - :user-id (required)
  - :password (required)
  - :return-url (default: \"aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==\")"
  [client {:keys [user-id password return-url]}]
  (let [missing (missing-input {:user-id user-id :password password})]
    (if (seq missing)
      (error-envelope :invalid-input
                      :missing-required-input
                      (str "Missing required input: " (str/join ", " (map name missing))))
      (try
        (let [default-opts (default-request-opts client)]
          (http/request (merge default-opts
                               {:method :get
                                :url (absolute-url client login-page-path)}))
          (http/request (merge default-opts
                               {:method :post
                                :url (absolute-url client login-submit-path)
                                :content-type :x-www-form-urlencoded
                                :form-params {"returnUrl" (or return-url "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==")
                                              "userId" user-id
                                              "password" password}
                                :headers (merge (:headers default-opts)
                                                {"Referer" (absolute-url client login-page-path)})}))
          (let [verify-res (http/request (merge default-opts
                                                {:method :get
                                                 :url (absolute-url client loan-status-path)
                                                 :headers (merge (:headers default-opts)
                                                                 {"Referer" (absolute-url client login-page-path)})}))
              authenticated? (and (= 200 (:status verify-res))
                                  (not (logged-out-page? (:body verify-res))))
              status (if authenticated? :ok :requires-login)]
            (swap! client assoc :last-login {:ok? authenticated?
                                             :user-id user-id})
            {:ok? authenticated?
             :status status
             :data {:authenticated? authenticated?
                    :user-id user-id}
             :error nil}))
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

(defn- normalize-manage-codes
  [manage-code]
  (->> (cond
         (nil? manage-code) []
         (sequential? manage-code) manage-code
         :else [manage-code])
       (map #(some-> % str str/trim))
       (remove str/blank?)
       vec))

(defn- build-search-query
  [{:keys [keyword manage-code library-code page per-page sort order]}]
  (let [manage-codes (normalize-manage-codes (or manage-code library-code))
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
               "searchSort" (or sort "SIMILAR")
               "searchOrder" (or order "DESC")
               "searchRecordCount" (str (or per-page 10))
               "searchBookClass" "ALL"}]
    (cond-> query
      (empty? manage-codes)
      ;; Default browser search checks all public libraries and no small libraries.
      (assoc "searchPbLibrary" "ALL"
             "searchSmLibrary" "")

      (seq manage-codes)
      ;; Browser re-search clears searchPbLibrary/searchSmLibrary when a subset is selected.
      ;; If searchPbLibrary=ALL is sent with searchLibraryArr=MU, the server ignores the subset
      ;; and returns every public library result.
      (assoc "searchPbLibrary" ""
             "searchSmLibrary" ""
             "searchLibraryArr" (if (= 1 (count manage-codes))
                                  (first manage-codes)
                                  manage-codes)))))

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
  (or (->> (.select item "a[onclick]")
           (map #(.attr ^Element % "onclick"))
           (some (fn [onclick]
                   (or (when-let [[_ manage-code reg-no]
                                  (re-find #"fnBandLillApplyPop\('([^']+)'\s*,\s*'([^']+)'\)"
                                           onclick)]
                         {:manage-code manage-code
                          :reg-no reg-no})
                       (when-let [[_ _prefix reg-no manage-code _site-code]
                                  (re-find #"fnLoanReservationApplyProc\('([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\s*,\s*'([^']+)'\)"
                                           onclick)]
                         {:manage-code manage-code
                          :reg-no reg-no})))))
      {:manage-code nil
       :reg-no nil}))

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
  "Run `search-books` command.

  opts:
  - :keyword (required)
  - :manage-code or :library-code (optional, single or multiple)
  - :page (default: 1)
  - :per-page (default: 10)
  - :sort (default: \"SIMILAR\")
  - :order (default: \"DESC\")"
  [client {:keys [keyword] :as opts}]
  (if (str/blank? (or keyword ""))
    (error-envelope :invalid-input
                    :missing-required-input
                    "Missing required input: keyword")
    (try
      (let [default-opts (default-request-opts client)
            query-params (build-search-query opts)
            res (http/request (merge default-opts
                                     {:method :get
                                      :url (absolute-url client search-path)
                                      :query-params query-params
                                      :headers (merge (:headers default-opts)
                                                      {"Referer" (absolute-url client "/intro/main/index.do")})}))
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

(defn- span-value-from-texts
  [texts pattern]
  (some (fn [s]
          (when-let [[_ v] (re-find pattern s)]
            (str/trim v)))
        texts))

(defn- parse-loans-from-article-list
  [doc]
  (->> (.select doc ".article-list > li")
       (remove #(some-> (.select ^Element % ".emptyNote") first))
       (map (fn [^Element li]
              (let [title (or (some-> (.select li "p.title a") first .text normalize-text)
                              (some-> (.select li "p.title") first .text normalize-text))
                    spans (->> (.select li "p.info span")
                               (map #(.text ^Element %))
                               (map normalize-text)
                               (remove nil?)
                               vec)
                    return-status (or (span-value-from-texts spans #"상태\s*:\s*(.+)") "")]
                (when (or title (seq spans))
                  {:title (or title "")
                   :loan-date (or (span-value-from-texts spans #"대출일\s*:\s*(.+)") "")
                   :due-date (or (span-value-from-texts spans #"반납예정일\s*:\s*(.+)") "")
                   :return-status return-status
                   :renewable? (parse-renewable? return-status)}))))
       (remove nil?)
       vec))

(defn- parse-loan-status-loans
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)
          table-loans (->> (.select doc "table")
                           (mapcat parse-loans-from-table)
                           vec)]
      (if (seq table-loans)
        table-loans
        (parse-loans-from-article-list doc)))))

(defn- returned-loan?
  [loan]
  (boolean (re-find returned-status-pattern
                    (or (:return-status loan) ""))))

(defn loan-status!
  "Run `loan-status` command.

  opts:
  - :include-history? (default: false)"
  [client {:keys [include-history?]}]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client loan-status-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
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
   "mobile-no" "handPhone"
   "mobileno" "handPhone"
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
                                                          tag)
                                             current-value (cond
                                                             (= "textarea" tag)
                                                             (.text field)

                                                             (= "select" tag)
                                                             (or (some-> (.select field "option[selected]") first (.attr "value"))
                                                                 (some-> (.select field "option") first (.attr "value"))
                                                                 "")

                                                             (contains? #{"checkbox" "radio"} (str/lower-case (or field-type "")))
                                                             (if (.hasAttr field "checked")
                                                               (.attr field "value")
                                                               "")

                                                             :else
                                                             (.attr field "value"))]
                                         {:name (.attr field "name")
                                          :type (or field-type "")
                                          :value current-value})))
                                (remove #(str/blank? (:name %)))
                                vec)}))
           vec))))

(defn- form-field-defaults
  [form-spec]
  (->> (:fields form-spec)
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

(defn- split-hand-phone
  [value]
  (let [raw (some-> value str str/trim)]
    (when-not (str/blank? raw)
      (let [parts (->> (str/split raw #"-")
                       (map str/trim)
                       (remove str/blank?)
                       vec)
            digits (str/replace raw #"\D" "")]
        (cond
          (= 3 (count parts))
          {"mobileNo1" (nth parts 0)
           "mobileNo2" (nth parts 1)
           "mobileNo3" (nth parts 2)}

          (= 11 (count digits))
          {"mobileNo1" (subs digits 0 3)
           "mobileNo2" (subs digits 3 7)
           "mobileNo3" (subs digits 7 11)}

          (= 10 (count digits))
          {"mobileNo1" (subs digits 0 3)
           "mobileNo2" (subs digits 3 6)
           "mobileNo3" (subs digits 6 10)}

          :else nil)))))

(defn- enrich-hope-book-phone-fields
  [payload]
  (let [mobile1 (get payload "mobileNo1")
        mobile2 (get payload "mobileNo2")
        mobile3 (get payload "mobileNo3")
        payload' (if (and (str/blank? (get payload "handPhone"))
                          (every? #(not (str/blank? (or % "")))
                                  [mobile1 mobile2 mobile3]))
                   (assoc payload "handPhone" (str mobile1 "-" mobile2 "-" mobile3))
                   payload)
        derived (split-hand-phone (get payload' "handPhone"))]
    (if-not (map? derived)
      payload'
      (reduce-kv (fn [acc k v]
                   (if (str/blank? (get acc k))
                     (assoc acc k v)
                     acc))
                 payload'
                 derived))))

(defn- build-hope-book-payload
  [form-spec request]
  (-> (merge (form-field-defaults form-spec)
             (stringify-field-map request))
      enrich-hope-book-phone-fields))

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
   {:keys [request manage-code submit? page-path submit-path]
    :as opts}]
  (try
    (let [default-opts (default-request-opts client)
          normalized-request (merge (if (map? request) request {})
                                    (when-not (str/blank? (or manage-code ""))
                                      {:manage-code manage-code}))
          resolved-page-path (or page-path default-hope-book-page-path)
          page-res (http/request (merge default-opts
                                        {:method :get
                                         :url (absolute-url client resolved-page-path)
                                         :headers (merge (:headers default-opts)
                                                         {"Referer" (absolute-url client "/intro/main/index.do")})}))
          page-status (:status page-res)
          page-html (:body page-res)
          page-logged-out? (logged-out-page? page-html)
          forms (extract-forms page-html)
          selected-form (select-hope-book-form forms)
          resolved-submit-path (or submit-path
                                   (:action selected-form)
                                   (guess-hope-book-submit-path page-html)
                                   default-hope-book-submit-path)
          prepared-payload (build-hope-book-payload selected-form normalized-request)
          base-data {:prepared-payload prepared-payload
                     :submit-attempted? (boolean submit?)
                     :submit-blocked? false
                     :result-message nil}]
      (cond
        (not= 200 page-status)
        (error-envelope :remote-error
                        :hope-book-page-request-failed
                        (str "Hope-book page request failed with status " page-status))

        page-logged-out?
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
        (let [submit-res (http/request (merge default-opts
                                              {:method :post
                                               :url (absolute-url client resolved-submit-path)
                                               :content-type :x-www-form-urlencoded
                                               :form-params prepared-payload
                                               :headers (merge (:headers default-opts)
                                                               {"Referer" (absolute-url client resolved-page-path)})}))
              submit-body (or (:body submit-res) "")
              status-code (:status submit-res)
              result-message (or (parse-hope-book-result-message submit-body)
                                 (parse-hope-book-result-message page-html))
              success? (= 200 status-code)
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
  [{:keys [manage-code reg-no apl-lib-code submit?]}]
  (vec
    (concat
      (when (str/blank? (or manage-code "")) [:manage-code])
      (when (str/blank? (or reg-no "")) [:reg-no])
      (when submit?
        (when (str/blank? (or apl-lib-code ""))
          [:apl-lib-code])))))

(defn- invalid-interloan-input
  [opts]
  (codes/invalid-interloan-input opts))

(defn- missing-interloan-popup-values
  [{:keys [give-lib-code user-key]}]
  (vec
    (concat
      (when (str/blank? (or give-lib-code "")) [:give-lib-code])
      (when (str/blank? (or user-key "")) [:user-key]))))

(defn- parse-select-value
  [doc field-name]
  (let [selected-selector (str "select[name='" field-name "'] option[selected]")
        option-selector (str "select[name='" field-name "'] option")]
    (or (some-> (.select doc selected-selector) first (.attr "value") normalize-text)
        (some-> (.select doc option-selector) first (.attr "value") normalize-text))))

(defn- parse-hidden-input-value
  [doc field-name]
  (some-> (.select doc (str "input[name='" field-name "']"))
          first
          (.attr "value")
          normalize-text))

(defn- parse-interloan-popup-values
  [html]
  (if (str/blank? html)
    {}
    (let [doc (Jsoup/parse html)]
      {:give-lib-code (parse-hidden-input-value doc "giveLibCode")
       :user-key (or (parse-hidden-input-value doc "userKey")
                     (parse-select-value doc "userKey"))})))

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
  "Request interlibrary loan.

  opts:
  - :manage-code (required)
  - :reg-no (required)
  - :apl-lib-code (required only when :submit? is true)
  - :give-lib-code (optional, auto-filled from popup when possible)
  - :user-key (optional, auto-filled from popup when possible)
  - :appendix-apply-yn (default: \"N\")
  - :submit? (default: false; dry-run unless true)
  - :allow-submit? (default: false)

  Required options:
  - :manage-code and :reg-no (from search/detail interloan target)
  - :apl-lib-code when :submit? is true.

  Code guide:
  - :manage-code is usually a 2-letter uppercase code (ex: `MA`, `MB`, `MS`).
  - :apl-lib-code and :give-lib-code are 6-digit numeric library codes.
  - :apl-lib-code values can be looked up from classpath resource snlib/lib-code.edn.
  - :give-lib-code and :user-key are normally derived from the popup response."
  [client
   opts]
  (let [normalized-opts (codes/normalize-interloan-input opts)
        {:keys [manage-code reg-no submit?]} normalized-opts
        missing (missing-interloan-input normalized-opts)
        invalid (invalid-interloan-input normalized-opts)]
    (if (seq missing)
      (error-envelope :invalid-input
                      :missing-required-input
                      (str "Missing required input: " (str/join ", " (map name missing))))
      (if (seq invalid)
        (error-envelope :invalid-input
                        :invalid-input-format
                        (str "Invalid input format: " (str/join ", " (map name invalid))))
        (try
          (let [default-opts (default-request-opts client)
                popup-res (http/request (merge default-opts
                                               {:method :get
                                                :url (absolute-url client interloan-popup-path)
                                                :query-params {"manageCode" manage-code
                                                               "regNo" reg-no}
                                                :headers (merge (:headers default-opts)
                                                                {"Referer" (absolute-url client "/intro/main/index.do")})}))
                popup-status (:status popup-res)
                popup-html (:body popup-res)
                popup-values (parse-interloan-popup-values popup-html)
                merged-opts (codes/normalize-interloan-input
                              (merge popup-values
                                     (into {}
                                           (remove (fn [[_ v]]
                                                     (or (nil? v)
                                                         (and (string? v) (str/blank? v))))
                                                   normalized-opts))))
                prepared-payload (build-interloan-payload merged-opts)
                popup-logged-out? (logged-out-page? popup-html)
                base-data {:prepared-payload prepared-payload
                           :submit-attempted? (boolean submit?)
                           :submit-blocked? false
                           :result-message nil}]
            (cond
              (not= 200 popup-status)
              (error-envelope :remote-error
                              :interloan-popup-request-failed
                              (str "Interloan popup request failed with status " popup-status))

              popup-logged-out?
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

              (seq (invalid-interloan-input merged-opts))
              (error-envelope :invalid-input
                              :invalid-input-format
                              (str "Invalid input format: "
                                   (str/join ", "
                                             (map name (invalid-interloan-input merged-opts)))))

              (seq (missing-interloan-popup-values merged-opts))
              (error-envelope :invalid-input
                              :missing-required-input
                              (str "Missing required input: "
                                   (str/join ", "
                                             (map name (missing-interloan-popup-values merged-opts)))))

              (not (interloan-submit-allowed? normalized-opts))
              {:ok? false
               :status :blocked
               :data (assoc base-data
                            :submit-blocked? true
                            :result-message "Interloan submit is blocked by default.")
               :error {:code :write-blocked
                       :message "Set :allow-submit? true or env SNLIB_ALLOW_INTERLOAN_SUBMIT=1 to submit."}}

              :else
              (let [submit-res (http/request (merge default-opts
                                                    {:method :post
                                                     :url (absolute-url client interloan-submit-path)
                                                     :content-type :x-www-form-urlencoded
                                                     :form-params prepared-payload
                                                     :headers (merge (:headers default-opts)
                                                                     {"Origin" (:base-url @client)
                                                                      "Referer" (str (absolute-url client interloan-popup-path)
                                                                                     "?manageCode=" manage-code
                                                                                     "&regNo=" reg-no)})}))
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
                            (.getMessage e))))))))

(defn interloan-request!
  "Alias of interlibrary-loan-request!.

  See interlibrary-loan-request! for required options and code format details."
  [client opts]
  (interlibrary-loan-request! client opts))

;; ---------------------------------------------------------------------------
;; my-info
;; ---------------------------------------------------------------------------

(def ^:private my-info-label-key
  {"아이디" :user-id
   "회원번호" :member-no
   "회원가입일" :join-date
   "개인정보동의 만료일" :privacy-expiry-date
   "휴대폰번호" :phone
   "이메일주소" :email})

(defn- parse-my-info
  [html]
  (if (str/blank? html)
    {}
    (let [doc (Jsoup/parse html)
          member-type (some-> (.select doc ".myInfo .memType strong") first .text normalize-text)
          items (->> (.select doc ".myInfo .dot-list li")
                     (reduce
                       (fn [acc ^Element li]
                         (let [text (.text li)
                               [label value] (str/split text #"\s*:\s*" 2)
                               label' (some-> label str/trim)
                               value' (some-> value str/trim)]
                           (if-let [k (get my-info-label-key label')]
                             (assoc acc k value')
                             acc)))
                       {}))]
      (cond-> items
        member-type (assoc :member-type member-type)))))

(defn my-info!
  [client _opts]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client my-info-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {}
         :error nil}

        (= 200 status-code)
        {:ok? true
         :status :ok
         :data (parse-my-info html)
         :error nil}

        :else
        (error-envelope :remote-error
                        :my-info-request-failed
                        (str "My info request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; article-list parser (shared by loan-history, interloan-status, etc.)
;; ---------------------------------------------------------------------------

(defn- extract-info-spans
  [^Element li]
  (->> (.select li "p.info span")
       (map #(.text ^Element %))
       (map normalize-text)
       (remove nil?)
       vec))

(defn- span-value
  [spans pattern]
  (some (fn [s]
          (when-let [[_ v] (re-find pattern s)]
            (str/trim v)))
        spans))

(defn- parse-article-list-count
  [html]
  (when-not (str/blank? html)
    (let [doc (Jsoup/parse html)]
      (some-> (.select doc ".boardFilter .count .themeFC")
              first
              .text
              (str/replace #"[^\d]" "")
              not-empty
              parse-int-safe))))

;; ---------------------------------------------------------------------------
;; loan-history
;; ---------------------------------------------------------------------------

(defn- parse-loan-history-items
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".article-list > li")
           (remove #(some-> (.select ^Element % ".emptyNote") first))
           (map (fn [^Element li]
                  (let [title (some-> (.select li "p.title a") first .text normalize-text)
                        spans (extract-info-spans li)]
                    {:title (or title "")
                     :reg-no (or (span-value spans #"등록번호\s*:\s*(.+)") "")
                     :call-no (or (span-value spans #"청구기호\s*:\s*(.+)") "")
                     :library (or (span-value spans #"소장도서관\s*:\s*(.+)") "")
                     :room (or (span-value spans #"자료실\s*:\s*(.+)") "")
                     :return-status (or (span-value spans #"상태\s*:\s*(.+)") "")
                     :loan-date (or (span-value spans #"대출일\s*:\s*(.+)") "")
                     :return-date (or (span-value spans #"반납일\s*:\s*(.+)") "")})))
           vec))))

(defn loan-history!
  [client _opts]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client loan-history-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {:loans [] :count 0}
         :error nil}

        (= 200 status-code)
        (let [items (parse-loan-history-items html)
              total (or (parse-article-list-count html) (count items))]
          {:ok? true
           :status :ok
           :data {:loans items :count total}
           :error nil})

        :else
        (error-envelope :remote-error
                        :loan-history-request-failed
                        (str "Loan history request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; reservation-status
;; ---------------------------------------------------------------------------

(defn- parse-reservation-items
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".article-list > li")
           (remove #(some-> (.select ^Element % ".emptyNote") first))
           (map (fn [^Element li]
                  (let [title (some-> (.select li "p.title a") first .text normalize-text)
                        title' (or title
                                   (some-> (.select li "p.title") first .text normalize-text))
                        spans (extract-info-spans li)]
                    {:title (or title' "")
                     :reg-no (or (span-value spans #"등록번호\s*:\s*(.+)") "")
                     :reservation-date (or (span-value spans #"예약일\s*:\s*(.+)") "")
                     :reservation-expiry (or (span-value spans #"예약만기일\s*:\s*(.+)") "")
                     :status (or (span-value spans #"상태\s*:\s*(.+)") "")
                     :library (or (span-value spans #"도서관\s*:\s*(.+)") "")})))
           vec))))

(defn reservation-status!
  [client _opts]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client reservation-status-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {:reservations [] :count 0}
         :error nil}

        (= 200 status-code)
        (let [items (parse-reservation-items html)
              total (or (parse-article-list-count html) (count items))]
          {:ok? true
           :status :ok
           :data {:reservations items :count total}
           :error nil})

        :else
        (error-envelope :remote-error
                        :reservation-status-request-failed
                        (str "Reservation status request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; interloan-status
;; ---------------------------------------------------------------------------

(defn- parse-interloan-status-items
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".article-list > li")
           (remove #(some-> (.select ^Element % ".emptyNote") first))
           (map (fn [^Element li]
                  (let [title (some-> (.select li "p.title") first .text normalize-text)
                        spans (extract-info-spans li)
                        cancel-key (some-> (.select li "a[onclick*=fnDooraeCancelProc]")
                                           first
                                           (.attr "onclick")
                                           (->> (re-find #"fnDooraeCancelProc\('(\d+)'\)"))
                                           second)]
                    {:title (or title "")
                     :reg-no (or (span-value spans #"등록번호\s*:\s*(.+)") "")
                     :call-no (or (span-value spans #"청구기호\s*:\s*(.+)") "")
                     :give-library (or (span-value spans #"제공도서관\s*:\s*(.+)") "")
                     :apl-library (or (span-value spans #"수령도서관\s*:\s*(.+)") "")
                     :status (or (span-value spans #"상태\s*:\s*(.+)") "")
                     :apply-date (or (span-value spans #"신청일\s*:\s*(.+)") "")
                     :applicant (or (span-value spans #"신청자\s*:\s*(.+)") "")
                     :cancel-key (or cancel-key "")})))
           vec))))

(defn interloan-status!
  [client _opts]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client interloan-status-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {:requests [] :count 0}
         :error nil}

        (= 200 status-code)
        (let [items (parse-interloan-status-items html)
              total (or (parse-article-list-count html) (count items))]
          {:ok? true
           :status :ok
           :data {:requests items :count total}
           :error nil})

        :else
        (error-envelope :remote-error
                        :interloan-status-request-failed
                        (str "Interloan status request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

;; ---------------------------------------------------------------------------
;; hope-book-list / hope-book-detail
;; ---------------------------------------------------------------------------

(defn- parse-hope-book-list-items
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".article-list > li")
           (remove #(some-> (.select ^Element % ".emptyNote") first))
           (map (fn [^Element li]
                  (let [title-el (first (.select li "p.title a"))
                        title (some-> title-el .text normalize-text)
                        rec-key (some-> title-el
                                        (.attr "onclick")
                                        (->> (re-find #"hopeBookDetail\((\d+)\)"))
                                        second)
                        spans (extract-info-spans li)]
                    {:title (or title "")
                     :library (or (span-value spans #"도서관\s*:\s*(.+)") "")
                     :apply-date (or (span-value spans #"신청일\s*:\s*(.+)") "")
                     :status (or (span-value spans #"상태\s*:\s*(.+)") "")
                     :rec-key (or rec-key "")})))
           vec))))

(defn hope-book-list!
  [client _opts]
  (try
    (let [default-opts (default-request-opts client)
          res (http/request (merge default-opts
                                   {:method :get
                                    :url (absolute-url client hope-book-list-path)
                                    :headers (merge (:headers default-opts)
                                                    {"Referer" (absolute-url client "/intro/main/index.do")})}))
          status-code (:status res)
          html (:body res)]
      (cond
        (and (= 200 status-code)
             (logged-out-page? html))
        {:ok? false
         :status :requires-login
         :data {:items [] :count 0}
         :error nil}

        (= 200 status-code)
        (let [items (parse-hope-book-list-items html)
              total (or (parse-article-list-count html) (count items))]
          {:ok? true
           :status :ok
           :data {:items items :count total}
           :error nil})

        :else
        (error-envelope :remote-error
                        :hope-book-list-request-failed
                        (str "Hope book list request failed with status " status-code))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))

(def ^:private hope-book-detail-label-key
  {"신청자" :applicant
   "연락처" :phone
   "이메일" :email
   "신청도서관" :library
   "희망도서명" :title
   "저자" :author
   "출판사" :publisher
   "출판연도" :publish-year
   "ISBN" :isbn
   "가격" :price
   "신청일" :apply-date
   "신청상태" :status
   "신청사유" :opinion})

(defn- parse-hope-book-detail
  [html]
  (if (str/blank? html)
    {}
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".board-view tr")
           (reduce
             (fn [acc ^Element tr]
               (let [th (some-> (.select tr "th") first .text normalize-text)
                     td (some-> (.select tr "td") first .text normalize-text)]
                 (if-let [k (get hope-book-detail-label-key th)]
                   (assoc acc k (or td ""))
                   acc)))
             {})))))

(defn hope-book-detail!
  "Run `hope-book-detail` command.

  opts:
  - :rec-key (required)"
  [client {:keys [rec-key]}]
  (if (str/blank? (or rec-key ""))
    (error-envelope :invalid-input
                    :missing-required-input
                    "Missing required input: rec-key")
    (try
      (let [default-opts (default-request-opts client)
            res (http/request (merge default-opts
                                     {:method :get
                                      :url (absolute-url client hope-book-detail-path)
                                      :query-params {"recKey" rec-key}
                                      :headers (merge (:headers default-opts)
                                                      {"Referer" (absolute-url client hope-book-list-path)})}))
            status-code (:status res)
            html (:body res)]
        (cond
          (and (= 200 status-code)
               (logged-out-page? html))
          {:ok? false
           :status :requires-login
           :data {}
           :error nil}

          (= 200 status-code)
          {:ok? true
           :status :ok
           :data (parse-hope-book-detail html)
           :error nil}

          :else
          (error-envelope :remote-error
                          :hope-book-detail-request-failed
                          (str "Hope book detail request failed with status " status-code))))
      (catch Exception e
        (error-envelope :remote-error
                        :http-request-failed
                        (.getMessage e))))))

;; ---------------------------------------------------------------------------
;; basket-list
;; ---------------------------------------------------------------------------

(defn- parse-basket-group-key
  [html]
  (when-not (str/blank? html)
    (some->> (re-find #"fnBasketGroupBookMore\((\d+)\)" html)
             second)))

(defn- parse-basket-group-info
  [html]
  (when-not (str/blank? html)
    (let [doc (Jsoup/parse html)]
      {:group-name (some-> (.select doc ".htitle") first .ownText normalize-text)
       :book-count (some-> (.select doc ".htitle .normal")
                           first .text
                           (str/replace #"[^\d]" "")
                           not-empty
                           parse-int-safe)})))

(defn- parse-basket-books
  [html]
  (if (str/blank? html)
    []
    (let [doc (Jsoup/parse html)]
      (->> (.select doc ".wishBookList .listWrap > li, .article-list > li")
           (remove #(some-> (.select ^Element % ".emptyNote") first))
           (map (fn [^Element li]
                  (let [title (or (some-> (.select li ".book_name") first .text normalize-text)
                                (some-> (.select li "p.title a") first .text normalize-text)
                                (some-> (.select li "p.title") first .text normalize-text))
                        author (some-> (.select li ".bk_writer") first .text normalize-text)
                        publish (some-> (.select li ".bk_publish") first .text normalize-text)
                        spans (extract-info-spans li)]
                    {:title (or title "")
                     :author (or author
                                 (span-value spans #"저자\s*:\s*(.+)")
                                 "")
                     :publisher (or publish
                                    (span-value spans #"출판사\s*:\s*(.+)")
                                    "")})))
           vec))))

(defn basket-list!
  "Run `basket-list` command.

  opts:
  - :group-key (optional, defaults to first group parsed from basket main page)"
  [client {:keys [group-key]}]
  (try
    (let [default-opts (default-request-opts client)
          main-res (http/request (merge default-opts
                                        {:method :get
                                         :url (absolute-url client basket-group-main-path)
                                         :headers (merge (:headers default-opts)
                                                         {"Referer" (absolute-url client "/intro/main/index.do")})}))
          main-status (:status main-res)
          main-html (:body main-res)]
      (cond
        (and (= 200 main-status)
             (logged-out-page? main-html))
        {:ok? false
         :status :requires-login
         :data {:groups [] :books [] :count 0}
         :error nil}

        (= 200 main-status)
        (let [resolved-group-key (or group-key (parse-basket-group-key main-html))
              group-info (parse-basket-group-info main-html)
              book-res (when resolved-group-key
                         (http/request (merge default-opts
                                              {:method :post
                                               :url (absolute-url client basket-group-book-list-path)
                                               :content-type :x-www-form-urlencoded
                                               :form-params {"searchGroupKey" resolved-group-key
                                                             "searchLibrary" "ALL"
                                                             "searchKey" "TITLE"
                                                             "searchValue" ""}
                                               :headers (merge (:headers default-opts)
                                                               {"Referer" (absolute-url client basket-group-main-path)})})))
              book-status (:status book-res)
              book-html (:body book-res)]
          (cond
            (and resolved-group-key
                 (not= 200 book-status))
            (error-envelope :remote-error
                            :basket-list-books-request-failed
                            (str "Basket list books request failed with status " book-status))

            (and resolved-group-key
                 (logged-out-page? book-html))
            {:ok? false
             :status :requires-login
             :data {:groups [] :books [] :count 0}
             :error nil}

            :else
            (let [books (if resolved-group-key
                          (parse-basket-books book-html)
                          [])]
              {:ok? true
               :status :ok
               :data {:group-key resolved-group-key
                      :group-name (:group-name group-info)
                      :book-count (:book-count group-info)
                      :books books
                      :count (count books)}
               :error nil})))

        :else
        (error-envelope :remote-error
                        :basket-list-request-failed
                        (str "Basket list request failed with status " main-status))))
    (catch Exception e
      (error-envelope :remote-error
                      :http-request-failed
                      (.getMessage e)))))
