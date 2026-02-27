(ns snlib-cli.html
  (:require
   [clojure.string :as str]))

(defn- data-attr
  [html attr-name]
  (some->> (re-find (re-pattern (str "data-" attr-name "=\"([^\"]*)\"")) html)
           second))

(defn- parse-bool
  [value]
  (= "true" (some-> value str/lower-case)))

(defn extract-login-result
  [html]
  {:success? (parse-bool (data-attr html "login-success"))
   :user-id (data-attr html "user-id")
   :message (data-attr html "message")})

(defn extract-search-results
  [html]
  (let [items (for [[_ isbn title author available?]
                    (re-seq #"<li class=\"search-item\" data-isbn=\"([^\"]+)\" data-title=\"([^\"]+)\" data-author=\"([^\"]+)\" data-available=\"([^\"]+)\"" html)]
                {:isbn isbn
                 :title title
                 :author author
                 :available? (parse-bool available?)})]
    {:total (count items)
     :items (vec items)}))

(defn extract-interlibrary-loan
  [html]
  {:request-id (data-attr html "request-id")
   :book-title (data-attr html "book-title")
   :pickup-library (data-attr html "pickup-library")
   :status (or (data-attr html "status") "draft")})

(defn extract-loan-status
  [html]
  (let [items (for [[_ title due-date status]
                    (re-seq #"<li class=\"loan-item\" data-title=\"([^\"]+)\" data-due-date=\"([^\"]+)\" data-status=\"([^\"]+)\"" html)]
                {:title title
                 :due-date due-date
                 :status status})]
    {:total (count items)
     :items (vec items)}))

(defn extract-wish-book
  [html]
  {:request-id (data-attr html "request-id")
   :title (data-attr html "title")
   :author (data-attr html "author")
   :status (or (data-attr html "status") "draft")})
