(ns snlib.codes
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str]))

(def ^:private manage-code-pattern #"^[A-Z]{2}$")
(def ^:private lib-code-pattern #"^\d{6}$")

(def ^:private known-lib-codes
  (delay
    (-> "lib-code.edn"
        io/resource
        slurp
        edn/read-string
        keys
        set)))

(defn normalize-manage-code
  [value]
  (some-> value str str/trim str/upper-case not-empty))

(defn normalize-lib-code
  [value]
  (some-> value str str/trim not-empty))

(defn valid-manage-code?
  [value]
  (boolean (re-matches manage-code-pattern (or value ""))))

(defn valid-lib-code?
  [value]
  (boolean (re-matches lib-code-pattern (or value ""))))

(defn known-lib-code?
  [value]
  (contains? @known-lib-codes value))

(defn normalize-interloan-input
  [{:keys [manage-code apl-lib-code give-lib-code] :as opts}]
  (assoc opts
         :manage-code (normalize-manage-code manage-code)
         :apl-lib-code (normalize-lib-code apl-lib-code)
         :give-lib-code (normalize-lib-code give-lib-code)))

(defn invalid-interloan-input
  [{:keys [manage-code apl-lib-code give-lib-code submit?]}]
  (vec
   (concat
    (when (and (some? manage-code)
               (not (str/blank? manage-code))
               (not (valid-manage-code? manage-code)))
      [:manage-code])
    (when (and submit?
               (some? apl-lib-code)
               (not (str/blank? apl-lib-code))
               (not (valid-lib-code? apl-lib-code)))
      [:apl-lib-code])
    (when (and (some? give-lib-code)
               (not (str/blank? give-lib-code))
               (not (valid-lib-code? give-lib-code)))
      [:give-lib-code]))))
