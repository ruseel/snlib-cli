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
(def ^:private default-return-url
  "aHR0cHM6Ly9zbmxpYi5nby5rci9pbnRyby9pbmRleC5kbw==")

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
     (or (str/includes? body "memberLogin.do")
         (str/includes? body "location.href='/intro/memberLogin.do'")))))

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
