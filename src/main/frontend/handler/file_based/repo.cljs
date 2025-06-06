(ns frontend.handler.file-based.repo
  "Repo fns for creating, loading and parsing file graphs"
  (:require [clojure.core.async :as async]
            [clojure.core.async.interop :refer [p->c]]
            [frontend.config :as config]
            [frontend.db :as db]
            [frontend.db.file-based.model :as file-model]
            [frontend.fs :as fs]
            [frontend.handler.file-based.file :as file-handler]
            [frontend.handler.repo-config :as repo-config-handler]
            [frontend.handler.route :as route-handler]
            [frontend.handler.ui :as ui-handler]
            [frontend.spec :as spec]
            [frontend.state :as state]
            [frontend.util :as util]
            [logseq.common.config :as common-config]
            [logseq.common.path :as path]
            [logseq.graph-parser :as graph-parser]
            [medley.core :as medley]
            [promesa.core :as p]
            [shadow.resource :as rc]))

(defn- create-contents-file
  [repo-url]
  (spec/validate :repos/url repo-url)
  (p/let [repo-dir (config/get-repo-dir repo-url)
          pages-dir (state/get-pages-directory)
          [org-path md-path] (map #(str pages-dir "/contents." %) ["org" "md"])
          contents-file-exist? (some #(fs/file-exists? repo-dir %) [org-path md-path])]
    (when-not contents-file-exist?
      (let [format (state/get-preferred-format)
            file-rpath (str "pages/" "contents." (config/get-file-extension format))
            default-content (case (name format)
                              "org" (rc/inline "templates/contents.org")
                              "markdown" (rc/inline "templates/contents.md")
                              "")]
        (p/let [_ (fs/mkdir-if-not-exists (path/path-join repo-dir pages-dir))
                file-exists? (fs/create-if-not-exists repo-url repo-dir file-rpath default-content)]
          (when-not file-exists?
            (file-handler/reset-file! repo-url file-rpath default-content {})))))))

(defn- create-custom-theme
  [repo-url]
  (spec/validate :repos/url repo-url)
  (let [repo-dir (config/get-repo-dir repo-url)
        path (str config/app-name "/" config/custom-css-file)
        file-rpath path
        default-content ""]
    (p/let [_ (fs/mkdir-if-not-exists (path/path-join repo-dir config/app-name))
            file-exists? (fs/create-if-not-exists repo-url repo-dir file-rpath default-content)]
      (when-not file-exists?
        (file-handler/reset-file! repo-url path default-content {})))))

(comment
  (defn- create-dummy-notes-page
    [repo-url content]
    (spec/validate :repos/url repo-url)
    (let [repo-dir (config/get-repo-dir repo-url)
          file-rpath (str (config/get-pages-directory) "/how_to_make_dummy_notes.md")]
      (p/let [_ (fs/mkdir-if-not-exists (path/path-join repo-dir (config/get-pages-directory)))
              _file-exists? (fs/create-if-not-exists repo-url repo-dir file-rpath content)]
        (file-handler/reset-file! repo-url file-rpath content {})))))

(defn create-config-file-if-not-exists
  "Creates a default logseq/config.edn if it doesn't exist"
  [repo-url]
  (spec/validate :repos/url repo-url)
  (let [repo-dir (config/get-repo-dir repo-url)
        app-dir config/app-name
        dir (path/path-join repo-dir app-dir)]
    (p/let [_ (fs/mkdir-if-not-exists dir)]
      (let [default-content config/config-default-content
            path (str app-dir "/" config/config-file)]
        (p/let [file-exists? (fs/create-if-not-exists repo-url repo-dir "logseq/config.edn" default-content)]
          (when-not file-exists?
            (file-handler/reset-file! repo-url path default-content {})
            (repo-config-handler/set-repo-config-state! repo-url default-content)))))))

(defn- create-default-files!
  [repo-url]
  (spec/validate :repos/url repo-url)
  (let [repo-dir (config/get-repo-dir repo-url)]
    (p/do! (fs/mkdir-if-not-exists (path/path-join repo-dir config/app-name))
           (fs/mkdir-if-not-exists (path/path-join repo-dir config/app-name config/recycle-dir))
           (fs/mkdir-if-not-exists (path/path-join repo-dir (config/get-journals-directory)))
           (create-config-file-if-not-exists repo-url)
           (create-contents-file repo-url)
           (create-custom-theme repo-url)
           (state/pub-event! [:page/create-today-journal repo-url]))))

(defn- parse-and-load-file!
  "Accept: .md, .org, .edn, .css"
  [repo-url file {:keys [new-graph? verbose]}]
  (->
   (p/let [result (file-handler/alter-file repo-url
                                           (:file/path file)
                                           (:file/content file)
                                           (merge (:stat file)
                                                  {:new-graph? new-graph?
                                                   :re-render-root? false
                                                   :from-disk? true}
                                                  ;; To avoid skipping the `:or` bounds for keyword destructuring
                                                  (when (some? verbose) {:verbose verbose})))]
     (state/set-parsing-state! (fn [m]
                                 (update m :finished inc)))
     result)
   (p/catch (fn [e]
              (println "Parse and load file failed: " (str (:file/path file)))
              (js/console.error e)
              (state/set-parsing-state! (fn [m]
                                          (update m :failed-parsing-files conj [(:file/path file) e])))
              (state/set-parsing-state! (fn [m]
                                          (update m :finished inc)))
              nil))))

(defn- parse-and-load-file-test-version!
  "Accept: .md, .org, .edn, .css"
  [repo-url file {:keys [new-graph? verbose]}]
  (try
    (let [result (file-handler/alter-file-test-version
                  repo-url
                  (:file/path file)
                  (:file/content file)
                  (merge (:stat file)
                         {:new-graph? new-graph?
                          :re-render-root? false
                          :from-disk? true}
                                                  ;; To avoid skipping the `:or` bounds for keyword destructuring
                         (when (some? verbose) {:verbose verbose})))]
      (state/set-parsing-state! (fn [m]
                                  (update m :finished inc)))
      result)
    (catch :default e
      (println "Parse and load file failed: " (str (:file/path file)))
      (js/console.error e)
      (state/set-parsing-state! (fn [m]
                                  (update m :failed-parsing-files conj [(:file/path file) e])))
      (state/set-parsing-state! (fn [m]
                                  (update m :finished inc))))))

(defn- after-parse
  [repo-url re-render? re-render-opts opts graph-added-chan]
  (when (or (:new-graph? opts) (not (:refresh? opts)))
    (create-default-files! repo-url))
  (when re-render?
    (ui-handler/re-render-root! re-render-opts))
  (state/pub-event! [:graph/added repo-url opts])
  (let [parse-errors (get-in @state/state [:graph/parsing-state repo-url :failed-parsing-files])]
    (when (seq parse-errors)
      (state/pub-event! [:file/parse-and-load-error repo-url parse-errors])))
  (state/reset-parsing-state!)
  (state/set-loading-files! repo-url false)
  (async/offer! graph-added-chan true))

(defn- parse-files-and-create-default-files-inner!
  [repo-url files delete-files delete-blocks re-render? re-render-opts opts]
  (let [supported-files (graph-parser/filter-files files)
        delete-data (->> (concat delete-files delete-blocks)
                         (remove nil?))
        indexed-files (medley/indexed supported-files)
        chan (async/to-chan! indexed-files)
        graph-added-chan (async/promise-chan)
        total (count supported-files)
        large-graph? (> total 1000)
        *page-names (atom #{})
        *page-name->path (atom {})]
    (when (seq delete-data) (db/transact! repo-url delete-data {:delete-files? true}))
    (state/set-current-repo! repo-url)
    (state/set-parsing-state! {:total (count supported-files)})
    ;; Synchronous for tests for not breaking anything
    (if util/node-test?
      (do
        (doseq [file supported-files]
          (state/set-parsing-state! (fn [m]
                                      (assoc m
                                             :current-parsing-file (:file/path file))))
          (parse-and-load-file-test-version! repo-url file (select-keys opts [:new-graph? :verbose])))
        (after-parse repo-url re-render? re-render-opts opts graph-added-chan))
      (async/go-loop []
        (if-let [item (async/<! chan)]
          (let [[idx file] item
                whiteboard? (common-config/whiteboard? (:file/path file))
                yield-for-ui? (or (not large-graph?)
                                  (zero? (rem idx 10))
                                  (<= (- total idx) 10)
                                  whiteboard?)]
            (state/set-parsing-state! (fn [m]
                                        (assoc m :current-parsing-file (:file/path file))))

            (when yield-for-ui? (async/<! (async/timeout 1)))

            (let [opts' (select-keys opts [:new-graph? :verbose])
                  ;; whiteboards might have conflicting block IDs so that db transaction could be failed
                  result (async/<! (p->c (parse-and-load-file! repo-url file opts')))
                  page-name (when (coll? result) ; result could be a promise
                              (some (fn [x] (when (and (map? x)
                                                       (:block/title x)
                                                       (= (:file/path file) (:file/path (:block/file x))))
                                              (:block/name x)))
                                    result))
                  page-exists? (and page-name (get @*page-names page-name))
                  _ (when page-exists?
                      (state/pub-event! [:notification/show
                                         {:content [:div
                                                    (util/format "The file \"%s\" will be skipped because another file \"%s\" has the same page title."
                                                                 (:file/path file)
                                                                 (get @*page-name->path page-name))]
                                          :status :warning
                                          :clear? false}]))
                  _ (when (and page-name (not page-exists?))
                      (swap! *page-names conj page-name)
                      (swap! *page-name->path assoc page-name (:file/path file)))]
              (recur)))
          (after-parse repo-url re-render? re-render-opts opts graph-added-chan))))
    graph-added-chan))

(defn- parse-files-and-create-default-files!
  [repo-url files delete-files delete-blocks re-render? re-render-opts opts]
  (parse-files-and-create-default-files-inner! repo-url files delete-files delete-blocks re-render? re-render-opts opts))

(defn parse-files-and-load-to-db!
  [repo-url files {:keys [delete-files delete-blocks re-render? re-render-opts _refresh?] :as opts
                   :or {re-render? true}}]
  (parse-files-and-create-default-files! repo-url files delete-files delete-blocks re-render? re-render-opts opts))

(defn load-new-repo-to-db!
  "load graph files to db."
  [repo-url {:keys [file-objs new-graph? empty-graph?]}]
  (spec/validate :repos/url repo-url)
  (route-handler/redirect-to-home!)
  (prn ::load-new-repo repo-url :empty-graph? empty-graph? :new-graph? new-graph?)
  (state/set-parsing-state! {:graph-loading? true})
  (let [config (or (when-let [content (some-> (first (filter #(= "logseq/config.edn" (:file/path %)) file-objs))
                                              :file/content)]
                     (repo-config-handler/read-repo-config content))
                   (state/get-config repo-url))
        ;; NOTE: Use config while parsing. Make sure it's the current journal title format
        ;; config should be loaded to state first
        _ (state/set-config! repo-url config)
        ;; remove :hidden files from file-objs, :hidden
        file-objs (common-config/remove-hidden-files file-objs config :file/path)]

    ;; Load to db even it's empty, (will create default files)
    (parse-files-and-load-to-db! repo-url file-objs {:new-graph? new-graph?
                                                     :empty-graph? empty-graph?})))

(defn load-repo-to-db!
  [repo-url {:keys [diffs file-objs refresh? new-graph? empty-graph?]}]
  (spec/validate :repos/url repo-url)
  (route-handler/redirect-to-home!)
  (prn ::load-repo-to-db! repo-url)
  (state/set-parsing-state! {:graph-loading? true})
  (let [config (or (when-let [content (some-> (first (filter #(= (config/get-repo-config-path) (:file/path %)) file-objs))
                                              :file/content)]
                     (repo-config-handler/read-repo-config content))
                   (state/get-config repo-url))
        ;; NOTE: Use config while parsing. Make sure it's the current journal title format
        _ (state/set-config! repo-url config)
        nfs-files (common-config/remove-hidden-files file-objs config :file/node-node-path)
        diffs (common-config/remove-hidden-files diffs config :path)
        load-contents (fn [files option]
                        (file-handler/load-files-contents!
                         repo-url
                         files
                         (fn [files-contents]
                           (parse-files-and-load-to-db! repo-url files-contents (assoc option :refresh? refresh?)))))]
    (cond
      (and (not (seq diffs)) nfs-files)
      (parse-files-and-load-to-db! repo-url nfs-files {:new-graph? new-graph?
                                                       :empty-graph? empty-graph?})

      :else
      (when (seq diffs)
        (let [filter-diffs (fn [type] (->> (filter (fn [f] (= type (:type f))) diffs)
                                           (map :path)))
              remove-files (filter-diffs "remove")
              modify-files (filter-diffs "modify")
              add-files (filter-diffs "add")
              delete-files (when (seq remove-files)
                             (db/delete-files remove-files))
              delete-blocks (file-model/delete-blocks repo-url remove-files true)
              delete-blocks (->>
                             (concat
                              delete-blocks
                              (file-model/delete-blocks repo-url modify-files false))
                             (remove nil?))
              delete-pages (if (seq remove-files)
                             (file-model/delete-pages-by-files remove-files)
                             [])
              add-or-modify-files (some->>
                                   (concat modify-files add-files)
                                   (remove nil?))
              options {:delete-files (concat delete-files delete-pages)
                       :delete-blocks delete-blocks
                       :re-render? true}]
          (if (seq nfs-files)
            (parse-files-and-load-to-db! repo-url nfs-files
                                         (assoc options
                                                :refresh? refresh?
                                                :re-render-opts {:clear-all-query-state? true}))
            (load-contents add-or-modify-files options)))))))
