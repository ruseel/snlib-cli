(ns snlib-cli.core
  (:require
   [snlib-cli.html :as html]))

(defn login
  "Extracts login result information from HTML and returns a map."
  [login-html]
  (html/extract-login-result login-html))

(defn search
  "Extracts search result information from HTML and returns a map."
  [search-html]
  (html/extract-search-results search-html))

(defn interlibrary-loan-request
  "Extracts interlibrary-loan draft data from HTML.
   Real submission is blocked during development/testing."
  [request-html]
  (assoc (html/extract-interlibrary-loan request-html)
         :submission-allowed? false
         :blocked-reason "development/test safety guard"))

(defn loan-status
  "Extracts current loan status data from HTML and returns a map."
  [loan-status-html]
  (html/extract-loan-status loan-status-html))

(defn wish-book-request
  "Extracts wish-book draft data from HTML.
   Real submission is blocked during development/testing."
  [request-html]
  (assoc (html/extract-wish-book request-html)
         :submission-allowed? false
         :blocked-reason "development/test safety guard"))
