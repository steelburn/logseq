(ns frontend.extensions.zotero.handler
  (:require [cljs.core.async :refer [<! go]]
            [cljs.core.async.interop :refer [p->c]]
            [clojure.string :as string]
            [frontend.db :as db]
            [frontend.extensions.zotero.api :as zotero-api]
            [frontend.extensions.zotero.extractor :as extractor]
            [frontend.extensions.zotero.setting :as setting]
            [frontend.handler.editor :as editor-handler]
            [frontend.handler.notification :as notification]
            [frontend.handler.page :as page-handler]
            [frontend.state :as state]
            [frontend.util.ref :as ref]
            [promesa.core :as p]))

;; TODO: test
(defn add [page-name type item]
  (go
    (let [key         (:key item)
          num-children (-> item :meta :num-children)
          api-fn      (case type
                        :notes       zotero-api/notes
                        :attachments zotero-api/attachments)
          first-block (case type
                        :notes       (setting/setting :notes-block-text)
                        :attachments (setting/setting :attachments-block-text))
          should-add? (case type
                        :notes       (setting/setting :include-notes?)
                        :attachments (setting/setting :include-attachments?))]
      (when (and should-add? (> num-children 0))
        (let [items    (<! (api-fn key))
              md-items (->> items
                            (map extractor/extract)
                            (remove string/blank?))]
          (when (not-empty md-items)
            (p->c
             (p/let [result (editor-handler/api-insert-new-block! first-block {:page page-name})]
               (when-let [id (:block/uuid result)]
                 (p/loop [items md-items]
                   (when-let [md-item (first items)]
                     (p/let [_ (editor-handler/api-insert-new-block!
                                md-item
                                {:block-uuid id
                                 :sibling?   false
                                 :before?    false})]
                       (p/recur (rest items))))))))))))))

(defn handle-command-zotero
  [id page-name]
  (state/clear-editor-action!)
  (editor-handler/insert-command! id (ref/->page-ref page-name) nil {}))

(defn- create-abstract-note!
  [page-name abstract-note]
  (when-not (string/blank? abstract-note)
    (p/let [block (editor-handler/api-insert-new-block!
                   "[[Abstract]]" {:page page-name})]
      (editor-handler/api-insert-new-block!
       abstract-note {:block-uuid (:block/uuid block)
                      :sibling? false}))))

(defn- create-page [page-name properties]
  (page-handler/<create!
   page-name
   {:redirect? false
    :format :markdown
    :properties properties}))

(defn create-zotero-page
  ([item]
   (create-zotero-page item {}))
  ([item {:keys [block-dom-id insert-command? notification?]
          :or {insert-command? true notification? true}}]
   (go
     (let [{:keys [page-name properties abstract-note]} (extractor/extract item)]
       (p->c
        (p/do!
         (when-not (string/blank? page-name)
           (if (db/page-exists? (string/lower-case page-name) "page")
             ;; FIXME: Overwrite if it has a zotero tag (which means created by Zotero)
             (if (setting/setting :overwrite-mode?)
               (page-handler/<delete!
                page-name
                (fn [] (create-page page-name properties)))
               (editor-handler/api-insert-new-block!
                ""
                {:page       page-name
                 :properties properties}))
             (create-page page-name properties))

           (create-abstract-note! page-name abstract-note)

           (add page-name :attachments item)

           (add page-name :notes item)

           (when insert-command?
             (handle-command-zotero block-dom-id page-name)
             (editor-handler/save-current-block!))

           (when notification?
             (notification/show! (str "Successfully added zotero item to page " page-name) :success)))))))))

(defn add-all [progress]
  (go
    (let [all-items (<! (zotero-api/all-top-items))]
      (reset! progress 30)
      (doseq [item all-items]
        (<! (create-zotero-page item {:insert-command? false :notification? false}))
        (swap! progress inc)))))
