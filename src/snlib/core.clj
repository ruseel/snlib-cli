(ns snlib.core
  (:require
   [clj-http.client :as http]
   [clj-http.cookies :as cookies]
   [clojure.string :as str]))

(def ^:private default-base-url "https://snlib.go.kr")
(def ^:private default-timeout-ms 20000)
(def ^:private default-user-agent "Mozilla/5.0 snlib-lib")

(def ^:private login-page-path "/intro/memberLogin.do")
(def ^:private login-submit-path "/intro/menu/10068/program/30025/memberLoginProc.do")
(def ^:private loan-status-path "/intro/menu/10060/program/30019/mypage/loanStatusList.do")
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
