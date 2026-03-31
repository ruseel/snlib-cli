(ns build
  (:refer-clojure :exclude [drop])
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [clojure.tools.build.api :as b]
   [clojure.xml :as xml])
  (:import
   (java.io File)
   (java.net URLEncoder)
   (java.nio.charset StandardCharsets)
   (java.security MessageDigest)
   (java.util Base64)))

(def target-dir "target")
(def class-dir (str target-dir "/classes"))
(def docs-dir (str target-dir "/docs"))
(def bundle-stage-root (str target-dir "/central-bundle"))
(def config-file "build-config.edn")
(def deps-file "deps.edn")

(defn help
  [_]
  (println "Build tasks:")
  (println "  clj -T:build clean")
  (println "  clj -T:build jar :version '\"0.1.0\"'")
  (println "  clj -T:build install :version '\"0.1.0-SNAPSHOT\"'")
  (println "  clj -T:build sign :version '\"0.1.0\"' [:gpg-key '\"KEYID\"'] [:gpg-passphrase '\"...\"']")
  (println "  clj -T:build bundle :version '\"0.1.0\"' [:gpg-key '\"KEYID\"'] [:gpg-passphrase '\"...\"']")
  (println "  clj -T:build deploy :version '\"0.1.0\"' [:server-id '\"central\"'] [:publishing-type '\"USER_MANAGED\"']")
  (println "  clj -T:build status :deployment-id '\"<deployment-id>\"' [:server-id '\"central\"']")
  (println "  clj -T:build publish :deployment-id '\"<deployment-id>\"' [:server-id '\"central\"']")
  (println "  clj -T:build drop :deployment-id '\"<deployment-id>\"' [:server-id '\"central\"']"))

(defn- fail
  [message]
  (throw (ex-info message {:message message})))

(defn- not-blank
  [s]
  (let [s' (some-> s str str/trim)]
    (when-not (str/blank? (or s' ""))
      s')))

(defn- keywordize-xml-tags
  [node]
  (cond
    (map? node)
    (-> node
        (update :tag #(if (keyword? %) % (keyword %)))
        (update :content (fn [children]
                           (mapv keywordize-xml-tags (or children [])))))

    :else
    node))

(defn- read-build-config
  []
  (-> config-file
      slurp
      edn/read-string))

(defn- project-paths
  []
  (->> (-> deps-file slurp edn/read-string :paths)
       (map str)
       (filter #(-> % io/file .exists))
       vec))

(defn- resource-paths
  []
  (->> (project-paths)
       (filter #(or (str/includes? % "resources")
                    (str/includes? % "data")))
       vec))

(defn- basis
  []
  (b/create-basis {:project deps-file}))

(defn- resolve-version
  [{:keys [version]}]
  (or (not-blank version)
      (not-blank (System/getenv "SNLIB_VERSION"))
      (fail "Version is required. Pass :version '\"0.1.0\"' or set SNLIB_VERSION.")))

(defn- lib-symbol
  [{:keys [lib]}]
  (cond
    (symbol? lib) lib
    (string? lib) (symbol lib)
    :else (fail (str "build-config.edn :lib must be a symbol or string, got: " (pr-str lib)))))

(defn- coord-parts
  [lib]
  (let [[group-id artifact-id] (str/split (str lib) #"/" 2)]
    (when (or (str/blank? group-id)
              (str/blank? artifact-id))
      (fail (str "Library coordinate must be group/artifact, got: " lib)))
    {:group-id group-id
     :artifact-id artifact-id}))

(defn- artifact-name
  [artifact-id version ext & [classifier]]
  (str artifact-id "-" version
       (when classifier
         (str "-" classifier))
       "." ext))

(defn- artifact-layout
  [config version]
  (let [lib (lib-symbol config)
        {:keys [group-id artifact-id]} (coord-parts lib)
        group-path (str/replace group-id "." "/")
        version-dir (format "%s/%s/%s/%s/%s" target-dir "maven" group-path artifact-id version)
        repo-dir (format "%s/%s/%s" group-path artifact-id version)]
    {:lib lib
     :group-id group-id
     :artifact-id artifact-id
     :version version
     :version-dir version-dir
     :repo-dir repo-dir
     :jar-file (str version-dir "/" (artifact-name artifact-id version "jar"))
     :pom-file (str version-dir "/" (artifact-name artifact-id version "pom"))
     :sources-jar-file (str version-dir "/" (artifact-name artifact-id version "jar" "sources"))
     :javadoc-jar-file (str version-dir "/" (artifact-name artifact-id version "jar" "javadoc"))
     :bundle-file (str target-dir "/" artifact-id "-" version "-central-bundle.zip")}))

(defn- element-if-present
  [tag value]
  (when-let [value' (not-blank value)]
    [tag value']))

(defn- pom-license
  [{:keys [name url distribution comments]}]
  (into
    [:license]
    (remove nil?
            [(element-if-present :name name)
             (element-if-present :url url)
             (element-if-present :distribution distribution)
             (element-if-present :comments comments)])))

(defn- pom-developer
  [{:keys [id name email url organization organizationUrl roles timezone]}]
  (into
    [:developer]
    (concat
      (remove nil?
              [(element-if-present :id id)
               (element-if-present :name name)
               (element-if-present :email email)
               (element-if-present :url url)
               (element-if-present :organization organization)
               (element-if-present :organizationUrl organizationUrl)
               (element-if-present :timezone timezone)])
      (when (seq roles)
        [(into [:roles]
               (keep (fn [role]
                       (element-if-present :role role)))
               roles)]))))

(defn- pom-data
  [{:keys [description url licenses developers]}]
  (into []
        (remove nil?)
        [(element-if-present :description description)
         (element-if-present :url url)
         (when (seq licenses)
           (into [:licenses]
                 (map pom-license)
                 licenses))
         (when (seq developers)
           (into [:developers]
                 (map pom-developer)
                 developers))]))

(defn- validate-central-metadata!
  [{:keys [description url scm licenses developers]}]
  (let [problems (concat
                   (when (str/blank? (or description ""))
                     [":description is required in build-config.edn"])
                   (when (str/blank? (or url ""))
                     [":url is required in build-config.edn"])
                   (when (str/blank? (or (:url scm) ""))
                     [":scm :url is required in build-config.edn"])
                   (when (empty? licenses)
                     ["At least one license is required in build-config.edn"])
                   (keep (fn [{:keys [name url]}]
                           (when (or (str/blank? (or name ""))
                                     (str/blank? (or url ""))
                                     (str/includes? (or name "") "TODO")
                                     (str/includes? (or url "") "TODO"))
                             (str "Update build-config.edn :licenses before deploying to Central: "
                                  (pr-str {:name name :url url}))))
                         licenses)
                   (when (empty? developers)
                     ["At least one developer is required in build-config.edn"]))]
    (when (seq problems)
      (fail (str "Central metadata validation failed:\n- "
                 (str/join "\n- " problems))))))

(defn- existing-doc-files
  []
  (->> ["README.md" "LICENSE" "LICENSE.md" "LICENSE.txt" "NOTICE" "NOTICE.md" "NOTICE.txt"]
       (map io/file)
       (filter #(.exists ^File %))
       vec))

(defn- ensure-dir!
  [path]
  (.mkdirs (io/file path))
  path)

(defn- run-command!
  [& args]
  (let [result (apply shell/sh args)]
    (when-not (zero? (:exit result))
      (throw (ex-info (str "Command failed: "
                           (str/join " " (map str (remove map? args)))
                           (when-let [err (not-blank (:err result))]
                             (str "\n" err)))
                      result)))
    result))

(defn clean
  [_]
  (b/delete {:path target-dir})
  (println (str "Deleted " target-dir)))

(defn- prepare-main-jar!
  [{:keys [lib jar-file]} version config]
  (let [src-dirs (project-paths)
        resources (resource-paths)
        basis' (basis)]
    (b/delete {:path class-dir})
    (ensure-dir! class-dir)
    (when (seq src-dirs)
      (b/copy-dir {:src-dirs src-dirs
                   :target-dir class-dir}))
    (b/write-pom {:class-dir class-dir
                  :lib lib
                  :version version
                  :basis basis'
                  :src-dirs src-dirs
                  :resource-dirs resources
                  :repos {}
                  :scm (:scm config)
                  :pom-data (pom-data config)})
    (ensure-dir! (.getParentFile (io/file jar-file)))
    (b/jar {:class-dir class-dir
            :jar-file jar-file})
    jar-file))

(defn- prepare-standalone-pom!
  [{:keys [lib pom-file]} version config]
  (let [src-dirs (project-paths)
        resources (resource-paths)
        basis' (basis)
        pom-stage (str target-dir "/pom-staging")]
    (b/delete {:path pom-stage})
    (ensure-dir! pom-stage)
    (ensure-dir! (.getParentFile (io/file pom-file)))
    (b/write-pom {:target pom-stage
                  :lib lib
                  :version version
                  :basis basis'
                  :src-dirs src-dirs
                  :resource-dirs resources
                  :repos {}
                  :scm (:scm config)
                  :pom-data (pom-data config)})
    (b/copy-file {:src (str (io/file pom-stage "pom.xml"))
                  :target pom-file})
    pom-file))

(defn- prepare-sources-jar!
  [{:keys [sources-jar-file]}]
  (let [source-stage (str target-dir "/sources")
        src-dirs (project-paths)]
    (b/delete {:path source-stage})
    (ensure-dir! source-stage)
    (when (seq src-dirs)
      (b/copy-dir {:src-dirs src-dirs
                   :target-dir source-stage}))
    (doseq [doc-file (existing-doc-files)]
      (b/copy-file {:src (str doc-file)
                    :target (str (io/file source-stage (.getName ^File doc-file)))}))
    (b/jar {:class-dir source-stage
            :jar-file sources-jar-file})
    sources-jar-file))

(defn- prepare-javadoc-jar!
  [{:keys [javadoc-jar-file artifact-id version]}]
  (let [javadoc-stage (str docs-dir "/javadoc")
        readme-file (io/file javadoc-stage "README.txt")]
    (b/delete {:path javadoc-stage})
    (ensure-dir! javadoc-stage)
    (spit readme-file
          (str artifact-id " " version "\n\n"
               "This project is implemented in Clojure. API docs live in source and README.md.\n"))
    (b/jar {:class-dir javadoc-stage
            :jar-file javadoc-jar-file})
    javadoc-jar-file))

(defn jar
  [opts]
  (let [config (read-build-config)
        version (resolve-version opts)
        layout (artifact-layout config version)]
    (prepare-main-jar! layout version config)
    (prepare-standalone-pom! layout version config)
    (prepare-sources-jar! layout)
    (prepare-javadoc-jar! layout)
    (doseq [path [(:jar-file layout)
                  (:pom-file layout)
                  (:sources-jar-file layout)
                  (:javadoc-jar-file layout)]]
      (println (str "Built " path)))
    layout))

(defn install
  [opts]
  (let [config (read-build-config)
        version (resolve-version opts)
        layout (jar opts)]
    (b/install {:basis (basis)
                :lib (lib-symbol config)
                :version version
                :jar-file (:jar-file layout)
                :class-dir class-dir})
    (println (str "Installed " (lib-symbol config) " " version " into local Maven repo."))
    layout))

(defn- hex-bytes
  [bytes]
  (apply str (map #(format "%02x" (bit-and % 0xff)) bytes)))

(defn- file-digest
  [algorithm file]
  (let [digest (MessageDigest/getInstance algorithm)
        buffer (byte-array 8192)]
    (with-open [input (io/input-stream file)]
      (loop []
        (let [read-count (.read input buffer)]
          (when (pos? read-count)
            (.update digest buffer 0 read-count)
            (recur)))))
    (hex-bytes (.digest digest))))

(defn- write-checksum!
  [algorithm file ext]
  (let [checksum (file-digest algorithm file)]
    (spit (str file ext) (str checksum "\n"))))

(defn- sign-file!
  [file gpg-key gpg-passphrase]
  (let [args (cond-> ["gpg" "--batch" "--yes" "--armor" "--detach-sign"]
               (not-blank gpg-key) (into ["--local-user" gpg-key])
               (some? gpg-passphrase) (into ["--pinentry-mode" "loopback"
                                             "--passphrase" gpg-passphrase])
               :always (conj (str file)))]
    (apply run-command! args)
    (write-checksum! "MD5" file ".md5")
    (write-checksum! "SHA-1" file ".sha1")
    file))

(defn sign
  [opts]
  (let [config (read-build-config)
        version (resolve-version opts)
        layout (jar opts)
        gpg-key (or (not-blank (:gpg-key opts))
                    (not-blank (System/getenv "SNLIB_GPG_KEY")))
        gpg-passphrase (or (:gpg-passphrase opts)
                           (System/getenv "SNLIB_GPG_PASSPHRASE"))]
    (validate-central-metadata! config)
    (doseq [file [(:jar-file layout)
                  (:pom-file layout)
                  (:sources-jar-file layout)
                  (:javadoc-jar-file layout)]]
      (sign-file! file gpg-key gpg-passphrase)
      (println (str "Signed " file)))
    layout))

(defn- relative-repo-path
  [layout file]
  (str (:repo-dir layout) "/" (.getName (io/file file))))

(defn bundle
  [opts]
  (let [config (read-build-config)
        version (resolve-version opts)
        layout (sign opts)
        stage-root (io/file bundle-stage-root)
        bundle-file (:bundle-file layout)
        files (mapcat (fn [base-file]
                        [base-file
                         (str base-file ".asc")
                         (str base-file ".md5")
                         (str base-file ".sha1")])
                      [(:jar-file layout)
                       (:pom-file layout)
                       (:sources-jar-file layout)
                       (:javadoc-jar-file layout)])]
    (validate-central-metadata! config)
    (b/delete {:path bundle-stage-root})
    (ensure-dir! bundle-stage-root)
    (doseq [file files]
      (b/copy-file {:src file
                    :target (str (io/file stage-root (relative-repo-path layout file)))}))
    (let [bundle-absolute (-> bundle-file io/file .getAbsolutePath)]
      (when (.exists (io/file bundle-file))
        (io/delete-file bundle-file))
      (run-command! "zip" "-rq" bundle-absolute "." {:dir bundle-stage-root})
      (println (str "Created " bundle-file))
      layout)))

(defn- xml-text
  [node]
  (->> (:content node)
       (filter string?)
       (apply str)
       str/trim))

(defn- child-text
  [node tag]
  (some (fn [child]
          (when (and (map? child)
                     (= tag (:tag child)))
            (xml-text child)))
        (:content node)))

(defn- server-credentials
  [server-id]
  (let [settings-file (io/file (System/getProperty "user.home") ".m2" "settings.xml")]
    (when-not (.exists settings-file)
      (fail (str "Missing ~/.m2/settings.xml for server id " server-id)))
    (let [settings (-> settings-file xml/parse keywordize-xml-tags)
          servers (let [nested (filter #(= :server (:tag %))
                                       (tree-seq #(and (map? %) (seq (:content %))) :content settings))]
                    (if (= :server (:tag settings))
                      (vec (cons settings nested))
                      (vec nested)))
          server (some #(when (= server-id (child-text % :id)) %) servers)
          username (some-> server (child-text :username) not-blank)
          password (some-> server (child-text :password) not-blank)]
      (when-not server
        (fail (str "Could not find server id '" server-id "' in ~/.m2/settings.xml")))
      (when (or (nil? username) (nil? password))
        (fail (str "Server id '" server-id "' is missing username/password in ~/.m2/settings.xml")))
      {:username username
       :password password})))

(defn- authorization-header
  [{:keys [username password]}]
  (let [token (.encodeToString (Base64/getEncoder)
                               (.getBytes (str username ":" password) StandardCharsets/UTF_8))]
    (str "Authorization: Bearer " token)))

(defn- url-encode
  [s]
  (URLEncoder/encode (str s) (str StandardCharsets/UTF_8)))

(defn- curl-request!
  [{:keys [method url header form-file dir]}]
  (let [args (cond-> ["curl" "-sS" "-w" "\n%{http_code}" "-X" method]
               header (into ["-H" header])
               form-file (into ["--form" (str "bundle=@" form-file ";type=application/octet-stream")])
               :always (conj url))
        result (apply shell/sh (cond-> args
                                 dir (conj {:dir dir})))
        out (or (:out result) "")
        lines (str/split-lines out)
        status-line (last lines)
        body (str/join "\n" (butlast lines))
        status (try
                 (Integer/parseInt (or status-line "0"))
                 (catch Exception _ 0))]
    (when (or (not= 0 (:exit result))
              (not (<= 200 status 299)))
      (throw (ex-info (str "HTTP request failed: " method " " url
                           "\nstatus: " status
                           (when-let [err (not-blank (:err result))]
                             (str "\nstderr: " err))
                           (when-let [body' (not-blank body)]
                             (str "\nbody: " body')))
                      {:method method
                       :url url
                       :status status
                       :stderr (:err result)
                       :body body})))
    {:status status
     :body body}))

(defn- deployment-url
  [deployment-id]
  (str "https://central.sonatype.com/api/v1/publisher/deployment/" deployment-id))

(defn- resolved-server-id
  [opts config]
  (or (not-blank (:server-id opts))
      (not-blank (System/getenv "SNLIB_CENTRAL_SERVER_ID"))
      (not-blank (:server-id config))
      "central"))

(defn deploy
  [opts]
  (let [config (read-build-config)
        version (resolve-version opts)
        layout (bundle opts)
        server-id (resolved-server-id opts config)
        header (authorization-header (server-credentials server-id))
        publishing-type (or (not-blank (:publishing-type opts))
                            (not-blank (System/getenv "SNLIB_CENTRAL_PUBLISHING_TYPE"))
                            "USER_MANAGED")
        deployment-name (or (not-blank (:deployment-name opts))
                            (str (:lib layout) ":" version))
        url (str "https://central.sonatype.com/api/v1/publisher/upload"
                 "?name=" (url-encode deployment-name)
                 "&publishingType=" (url-encode publishing-type))
        {:keys [body]} (curl-request! {:method "POST"
                                       :url url
                                       :header header
                                       :form-file (:bundle-file layout)})
        deployment-id (not-blank body)]
    (println (str "Uploaded " (:bundle-file layout)))
    (println (str "Deployment ID: " deployment-id))
    (println (str "Next: clj -T:build status :deployment-id '\"" deployment-id "\"'"
                  " :server-id '\"" server-id "\"'"))
    (when (= "USER_MANAGED" (str/upper-case publishing-type))
      (println (str "When status reaches VALIDATED, publish with:"
                    " clj -T:build publish :deployment-id '\"" deployment-id "\"'"
                    " :server-id '\"" server-id "\"'")))
    {:deployment-id deployment-id
     :publishing-type publishing-type}))

(defn status
  [{:keys [deployment-id] :as opts}]
  (let [config (read-build-config)
        deployment-id (or (not-blank deployment-id)
                          (fail "deployment-id is required"))
        server-id (resolved-server-id opts config)
        header (authorization-header (server-credentials server-id))
        url (str "https://central.sonatype.com/api/v1/publisher/status?id="
                 (url-encode deployment-id))
        {:keys [body]} (curl-request! {:method "POST"
                                       :url url
                                       :header header})]
    (println body)
    body))

(defn publish
  [{:keys [deployment-id] :as opts}]
  (let [config (read-build-config)
        deployment-id (or (not-blank deployment-id)
                          (fail "deployment-id is required"))
        server-id (resolved-server-id opts config)
        header (authorization-header (server-credentials server-id))]
    (curl-request! {:method "POST"
                    :url (deployment-url deployment-id)
                    :header header})
    (println (str "Published deployment " deployment-id))))

(defn drop
  [{:keys [deployment-id] :as opts}]
  (let [config (read-build-config)
        deployment-id (or (not-blank deployment-id)
                          (fail "deployment-id is required"))
        server-id (resolved-server-id opts config)
        header (authorization-header (server-credentials server-id))]
    (curl-request! {:method "DELETE"
                    :url (deployment-url deployment-id)
                    :header header})
    (println (str "Dropped deployment " deployment-id))))
