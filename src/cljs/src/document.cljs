;; cljs/src/document.cljs: document model that interacts with the backend.
;; Copyright 2011-2012, Vixu.com, F.M. (Filip) de Waard <fmw@vixu.com>.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;; 
;; http://www.apache.org/licenses/LICENSE-2.0
;; 
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns vix.document
  (:require [cljs.reader :as reader]
            [vix.util :as util]
            [goog.net.XhrIo :as xhrio]
            [goog.net.EventType :as event-type]
            [goog.events :as events]
            [goog.structs.Map :as Map]))

(defn add-initial-slash [slug]
  (if (= (first slug) "/")
    slug
    (str "/" slug)))

(defn request [uri callback method data]
  (let [req (new goog.net.XhrIo)]
    (events/listen req goog.net.EventType/COMPLETE callback)
    (. req (send uri
                 method
                 (pr-str data)
                 (new goog.structs.Map
                      "Content-Type" "text/plain; charset=utf-8")))))

(defn request-doc [slug callback method data]
  (request (str "/_api/clj/_document" slug) callback method data))

(defn request-feed [language feed-name callback method data]
  (request (str "/_api/clj/_feed/" language "/" feed-name)
           callback
           method
           data))

(defn get-doc [slug callback]
  (request-doc slug callback "GET" nil))

(defn delete-doc [slug callback]
  (request-doc slug callback "DELETE" nil))

(defn create-doc [slug callback doc]
  (request-doc slug callback "POST" doc))

(defn update-doc [slug callback doc]
  (request-doc slug callback "PUT" doc))

(defn get-documents-for-feed
  [language feed-name callback & [limit startkey-published startkey_docid]]
  (let [base-uri (str "/_api/clj/"
                      language
                      "/"
                      feed-name
                      "/_list-documents")]
    (xhrio/send (if (some nil? [startkey-published startkey_docid limit])
                  (if limit
                    (str base-uri "?limit=" limit)
                    base-uri)
                  (str base-uri
                       "?limit=" limit
                       "&startkey-published=" startkey-published
                       "&startkey_docid=" startkey_docid))
                callback)))

(defn get-feeds-list
  [callback & [default-document-type language]]
  (xhrio/send (str "/_api/clj/_list-feeds"
                   (when default-document-type
                     (str "?default-document-type=" default-document-type))
                   (when language
                     (if default-document-type
                       (str "&language=" language)
                       (str "?language=" language))))
              callback))

(defn get-feed [language feed-name callback]
  (request-feed language feed-name callback "GET" nil))

(defn create-feed [{:keys [language name] :as feed-doc} callback]
  (request-feed language name callback "POST" feed-doc))

(defn update-feed [{:keys [language name] :as feed-doc} callback]
  (request-feed language name callback "PUT" feed-doc))

(defn delete-feed [{:keys [language name] :as feed-doc} callback]
  (request-feed language name callback "DELETE" feed-doc))

(defn delete-feed-shortcut [language feed-name callback]
  (get-feed language
            feed-name
            (fn [e]
              (let [xhr (.-target e)
                    status (. xhr (getStatus))]
                (if (= status 200)
                  (let [feed (reader/read-string
                              (. xhr (getResponseText)))]
                    (delete-feed feed callback)))))))