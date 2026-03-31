#!/usr/bin/env bb

(require
  '[babashka.fs :as fs]
  '[babashka.process :as p]
  '[clojure.string :as str])

(def repo-root
  (-> *file* fs/path fs/parent fs/parent fs/normalize))

(def skill-source
  (fs/path repo-root "skills" "snlib-cli"))

(def references-dir
  (fs/path skill-source "references"))

(def publish-defaults
  {:slug "snlib-cli"
   :name "snlib-cli"
   :tags ["latest"]})

(defn usage
  []
  (println "Usage:")
  (println "  scripts/clawhub-release.bb prepare")
  (println "  scripts/clawhub-release.bb publish --version VERSION [--slug SLUG] [--name NAME] [--tags TAGS] [--changelog TEXT]")
  (println)
  (println "Commands:")
  (println "  prepare   Generate skills/snlib-cli/references/*.md from src/snlib/*.edn.")
  (println "  publish   Run prepare, then execute clawhub skill publish against skills/snlib-cli.")
  (println)
  (println "Options:")
  (println "  --version VERSION  Required for publish")
  (println "  --slug SLUG        Skill slug (default: snlib-cli)")
  (println "  --name NAME        Display name (default: snlib-cli)")
  (println "  --tags TAGS        Comma-separated tags (default: latest)")
  (println "  --changelog TEXT   Optional changelog for publish"))

(defn fail
  [message]
  (binding [*out* *err*]
    (println (str "ERROR: " message)))
  (System/exit 1))

(defn parse-args
  [args]
  (loop [[arg & more] args
         parsed {}]
    (cond
      (nil? arg)
      parsed

      (= "--version" arg)
      (let [[value & rest] more]
        (recur rest (assoc parsed :version (or value
                                               (fail "--version requires a value")))))

      (= "--slug" arg)
      (let [[value & rest] more]
        (recur rest (assoc parsed :slug (or value
                                            (fail "--slug requires a value")))))

      (= "--name" arg)
      (let [[value & rest] more]
        (recur rest (assoc parsed :name (or value
                                            (fail "--name requires a value")))))

      (= "--tags" arg)
      (let [[value & rest] more]
        (recur rest (assoc parsed :tags (or value
                                            (fail "--tags requires a value")))))

      (= "--changelog" arg)
      (let [[value & rest] more]
        (recur rest (assoc parsed :changelog (or value
                                                 (fail "--changelog requires a value")))))

      (str/starts-with? arg "--")
      (fail (str "Unknown option: " arg))

      :else
      (update parsed :positionals (fnil conj []) arg))))

(defn generated-reference
  [relative-source]
  (let [source-path (fs/path repo-root relative-source)
        file-name (fs/file-name source-path)
        body (slurp (str source-path))]
    (str "# " file-name "\n\n"
         "Generated from `" relative-source "`.\n\n"
         "```edn\n"
         body
         (when-not (str/ends-with? body "\n") "\n")
         "```\n")))

(defn write-generated-references!
  []
  (fs/create-dirs references-dir)
  (spit (str (fs/path references-dir "lib-code.md"))
        (generated-reference "src/snlib/lib-code.edn"))
  (spit (str (fs/path references-dir "manage-code.md"))
        (generated-reference "src/snlib/manage-code.edn")))

(defn prepare!
  [_opts]
  (write-generated-references!)
  (println (str "Prepared ClawHub references in " references-dir))
  skill-source)

(defn run!
  [cmd]
  (let [result (apply p/shell {:out :inherit
                               :err :inherit}
                       cmd)]
    (when-not (zero? (:exit result))
      (System/exit (:exit result)))))

(defn publish!
  [opts]
  (let [version (:version opts)
        _ (when (str/blank? (or version ""))
            (fail "--version is required for publish"))
        target-root (prepare! opts)
        slug (or (:slug opts) (:slug publish-defaults))
        name (or (:name opts) (:name publish-defaults))
        tags (or (:tags opts)
                 (str/join "," (:tags publish-defaults)))
        tag-values (->> (str/split tags #",")
                        (map str/trim)
                        (remove str/blank?))
        cmd (cond-> ["clawhub" "publish" (str target-root)
                     "--slug" slug
                     "--name" name
                     "--version" version]
              (seq tag-values) (into (mapcat (fn [tag] ["--tags" tag]) tag-values))
              (:changelog opts) (into ["--changelog" (:changelog opts)]))]
    (println (str "Running: " (str/join " " cmd)))
    (run! cmd)))

(let [[command & more] *command-line-args*
      {:keys [positionals] :as opts} (parse-args more)]
  (when (seq positionals)
    (fail (str "Unexpected arguments: " (str/join " " positionals))))
  (case command
    ("-h" "--help" nil) (usage)
    "prepare" (prepare! opts)
    "publish" (publish! opts)
    (fail (str "Unknown command: " command))))
