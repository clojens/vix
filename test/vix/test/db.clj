; test/vix/test/db.clj tests for db namespace.
; Copyright 2011, F.M. (Filip) de Waard <fmw@vix.io>.
;
; Licensed under the Apache License, Version 2.0 (the "License");
; you may not use this file except in compliance with the License.
; You may obtain a copy of the License at
;
; http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

(ns vix.test.db
  (:use [vix.db] :reload)
  (:use [clojure.test]
        [clojure.contrib.json :only [read-json]])
  (:require [couchdb [client :as couchdb]]
            [clj-http.client :as http]))

(defn couchdb-id? [s]
  (re-matches #"^[a-z0-9]{32}$" s))

(defn couchdb-rev?
  ([s] (couchdb-rev? 1 s))
  ([rev-num s] (re-matches (re-pattern (str "^" rev-num "-[a-z0-9]{32}$")) s)))

(defn iso-date? [s]
  (re-matches #"^[\d]{4}-[\d]{2}-[\d]{2}T[\d]{2}:[\d]{2}:[\d]{2}\.[\d]{1,4}Z" s))

(defn random-lower-case-string [length]
  ; to include uppercase
  ; (let [ascii-codes (concat (range 48 58) (range 66 91) (range 97 123))]
  (let [ascii-codes (concat (range 48 58) (range 97 123))]
    (apply str (repeatedly length #(char (rand-nth ascii-codes))))))

(def +test-db+ (str "vix-test-" (random-lower-case-string 20)))
(def +test-server+ "http://localhost:5984/")

(defn database-fixture [f]
  (couchdb/database-create
   +test-server+ +test-db+)
  (f)
  (couchdb/database-delete +test-server+ +test-db+))

(use-fixtures :each database-fixture)

(deftest test-view-sync
  (testing "Create new view"
    (do
      (view-sync +test-server+
                 +test-db+
                 "test"
                 "by_slug"
                 {:map (str "function(doc) {\n"
                            "    emit(doc.slug, doc);\n"
                            "}\n")}))
           
    (let [view-doc (read-json (:body (http/get
                                       (str +test-server+
                                            +test-db+
                                            "/_design/test"))))]
      (is (= (:map (:by_slug (:views view-doc)))
             (str "function(doc) {\n"
                  "    emit(doc.slug, doc);\n"
                  "}\n")))))

  (testing "Update existing view"
    (do
      (view-sync +test-server+
                 +test-db+
                 "test"
                 "by_slug"
                 {:map (str "function(d) {\n"
                            "    emit(d.slug, d);\n"
                            "}\n")
                  :reduce (str "function(key, v, rereduce) {\n"
                               "    return sum(v);\n"
                               "}\n")}))
           
    (let [view-doc (read-json (:body (http/get
                                       (str +test-server+
                                            +test-db+
                                            "/_design/test"))))]
      (is (= (:map (:by_slug (:views view-doc)))
             (str "function(d) {\n"
                  "    emit(d.slug, d);\n"
                  "}\n")))
      (is (= (:reduce (:by_slug (:views view-doc)))
             (str "function(key, v, rereduce) {\n"
                  "    return sum(v);\n"
                  "}\n"))))))


(deftest test-create-views
  (create-views +test-server+ +test-db+ "views" views)
  (let [view-doc (read-json (:body (http/get
                     (str +test-server+
                          +test-db+
                          "/_design/views"))))]

    (is (= (count (:views view-doc)) 4))
    
    (is (= (:map (:feeds (:views view-doc)))
           (str "function(doc) {\n"
                "    if(doc.type === \"feed\") {\n"
                "        emit(doc.name, doc);\n"
                "    }\n"
                "}")))
    
    (is (= (:map (:by_slug (:views view-doc)))
           (str "function(doc) {\n"
                "    if(doc.type === \"document\") {\n"
                "        emit(doc.slug, doc);\n"
                "    }\n"
                "}\n")))
    
    (is (= (:map (:by_feed (:views view-doc)))
           (str "function(doc) {\n"
                "    if(doc.type === \"document\") {\n"
                "        emit([doc.feed, doc.published], doc);\n"
                "    }\n"
                "}\n")))

    (is (= (:map (:by_username (:views view-doc)))
           (str "function(doc) {\n"
                "    if(doc.type === \"user\") {\n"
                "        emit(doc.username, doc);\n"
                "    }\n"
                "}\n")))))

(deftest test-encode-view-options
  (is (= (encode-view-options {:key "blog" :include_docs true})
         "key=%22blog%22&include_docs=true"))
  
  (is (= (encode-view-options {:endkey ["blog"]
                               :startkey ["blog" "2999"]
                               :descending true
                               :include_docs true})                                  
         (str "endkey=%5B%22blog%22%5D&startkey=%5B%22blog%22%2C%222999%22%5D"
              "&descending=true&include_docs=true"))))

(deftest test-view-get
  (let [doc-1 (create-document +test-server+ +test-db+ "blog" {:title "foo"
                                                               :slug "/blog/foo"
                                                               :content "bar"
                                                               :draft true})

        doc-2 (create-document +test-server+ +test-db+ "blog" {:title "foo"
                                                               :slug "/blog/foo"
                                                               :content "bar"
                                                               :draft true})]
    
    (testing "Test if view-get is working and if views are added automatically"
      (let [entries (:rows (view-get
                            +test-server+
                            +test-db+
                            "views"
                            "by_feed"
                            {:endkey ["blog"]
                             :startkey ["blog" "2999"]
                             :include_docs true
                             :descending true}))

            feed (map #(:value %) entries)]
    
        (is (= (count feed) 2))

        (is (some #{doc-1} feed))
        (is (some #{doc-2} feed))))

    (testing "Test if views are re-synced if missing (new?) view is requested"
      (do
        (couchdb/document-delete +test-server+ +test-db+ "_design/views")
        ; add views with :by_feed missing (to simulate upgrading a vix deployment
        ; to a new version with an extra view)
        (create-views +test-server+ +test-db+ "views" (dissoc views :by_feed)))

      (let [entries (:rows (view-get
                            +test-server+
                            +test-db+
                            "views"
                            "by_feed"
                            {:endkey ["blog"]
                             :startkey ["blog" "2999"]
                             :descending true}))

            feed (map #(:value %) entries)]
    
        (is (= (count feed) 2))

        (is (some #{doc-1} feed))
        (is (some #{doc-2} feed))))))

(deftest test-get-document
  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
      :slug "/blog/foo"
      :content "bar"
      :draft true}))

  ; as we didn't create the view manually here, this test also implies
  ; views are created automatically by get-document
  (let [document (get-document +test-server+ +test-db+ "/blog/foo")]
        (is (couchdb-id? (:_id document)))
        (is (couchdb-rev? (:_rev document)))
        (is (iso-date? (:published document)))
        (is (= (:feed document) "blog"))
        (is (= (:title document) "foo"))
        (is (= (:slug document) (str "/blog/foo")))
        (is (= (:content document) "bar"))
        (is (true? (:draft document)))))

(deftest test-get-feed
  (do
    (create-feed +test-server+
                 +test-db+
                 {:title "Weblog"
                  :subtitle "Vix Weblog!"
                  :name "blog"
                  :default-slug-format "/{document-title}"
                  :default-document-type "with-description"}))

  (let [feed (get-feed +test-server+ +test-db+ "blog")]
    (is (= (:type feed) "feed"))
    (is (couchdb-id? (:_id feed)))
    (is (couchdb-rev? (:_rev feed)))
    (is (iso-date? (:created feed)))
    (is (= (:title feed) "Weblog"))
    (is (= (:subtitle feed) "Vix Weblog!"))
    (is (= (:name feed) "blog"))
    (is (= (:default-slug-format feed) "/{document-title}"))
    (is (= (:default-document-type feed) "with-description"))))

(deftest test-get-unique-slug
  (is (= (get-unique-slug +test-server+ +test-db+ "/blog/foo") "/blog/foo"))

  (do
    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
      :slug "/blog/foo-1234567890"
      :content "bar"
      :draft true})

    (create-document
      +test-server+
      +test-db+
      "blog"
      {:title "foo"
      :slug "/blog/foo-1234567891"
      :content "bar"
      :draft true}))

  ; this should retrieve the next available slug:
  (is (= (get-unique-slug +test-server+ +test-db+ "/blog/foo-1234567890")
         "/blog/foo-1234567892")))

(deftest test-create-document
  (testing "Test document creation"
    (let [document (create-document
                     +test-server+
                     +test-db+
                     "blog"
                     {:title "foo"
                      :slug "/blog/foo"
                      :content "bar"
                      :draft false})]

      (is (couchdb-id? (:_id document)))
      (is (couchdb-rev? (:_rev document)))
      (is (iso-date? (:published document)))
      (is (= (:type document) "document"))
      (is (= (:feed document) "blog"))
      (is (= (:title document) "foo"))
      (is (= (:slug document) "/blog/foo"))
      (is (= (:content document) "bar"))
      (is (false? (:draft document)))))

  (testing "Test if slugs are correctly autoincremented"
    (dotimes [n 10]
      (let [document (create-document
                       +test-server+
                       +test-db+
                       "blog"
                       {:title "foo"
                        :slug "/blog/foo"
                        :content "bar"
                        :draft true})]

        (is (couchdb-id? (:_id document)))
        (is (couchdb-rev? (:_rev document)))
        (is (iso-date? (:published document)))
        (is (= (:feed document) "blog"))
        (is (= (:title document) "foo"))
        (is (= (:slug document) (str "/blog/foo-" (+ n 2))))
        (is (= (:content document) "bar"))
        (is (true? (:draft document)))))))

(deftest test-create-feed
  (let [feed (create-feed +test-server+
                          +test-db+
                          {:title "Weblog"
                           :subtitle "Vix Weblog!"
                           :name "blog"
                           :default-slug-format "/{document-title}"
                           :default-document-type "with-description"})]
    
    (is (= (:type feed) "feed"))
    (is (couchdb-id? (:_id feed)))
    (is (couchdb-rev? (:_rev feed)))
    (is (iso-date? (:created feed)))
    (is (= (:title feed) "Weblog"))
    (is (= (:subtitle feed) "Vix Weblog!"))
    (is (= (:name feed) "blog"))
    (is (= (:default-slug-format feed) "/{document-title}"))
    (is (= (:default-document-type feed) "with-description"))))

(deftest test-get-documents-for-feed
  (let [doc-1 (create-document +test-server+ +test-db+ "blog" {:title "foo"
                                                               :slug "/blog/foo"
                                                               :content "bar"
                                                               :draft true})

        doc-2 (create-document +test-server+ +test-db+ "blog" {:title "foo"
                                                               :slug "/blog/foo"
                                                               :content "bar"
                                                               :draft true})
        
        feed (get-documents-for-feed +test-server+ +test-db+ "blog")]

    (is (= (count feed) 2))

    (is (some #{doc-1} feed))
    (is (some #{doc-2} feed))))

(deftest test-list-feeds
  (let [blog-feed (create-feed +test-server+
                               +test-db+
                               {:title "Weblog"
                                :subtitle "Vix Weblog!"
                                :name "blog"
                                :default-slug-format "/{feed-name}/{document-title}"
                                :default-document-type "with-description"})
        pages-feed (create-feed +test-server+
                                +test-db+
                                {:title "Pages"
                                 :subtitle "Web Pages"
                                 :name "pages"
                                 :default-slug-format "/{document-title}"
                                 :default-document-type "standard"})
        images-feed (create-feed +test-server+
                                 +test-db+
                                 {:title "Images"
                                  :subtitle "Internal feed with images"
                                  :name "images"
                                  :default-slug-format "/media/{document-title}"
                                  :default-document-type "image"})
        feeds (list-feeds +test-server+ +test-db+)]

    (is (= (count feeds) 3))

    (is (some #{blog-feed} feeds))
    (is (some #{pages-feed} feeds))
    (is (some #{images-feed} feeds))))

(deftest test-update-document
  (let [new-doc (create-document
                  +test-server+
                  +test-db+
                  "blog"
                  {:title "foo"
                   :slug "/blog/bar"
                   :content "bar"
                   :draft false})
        updated-doc (update-document
                      +test-server+
                      +test-db+
                      "/blog/bar"
                      (assoc new-doc :title "hic sunt dracones"))]
    (is (= (get-document +test-server+ +test-db+ "/blog/bar") updated-doc))
    (is (couchdb-rev? 2 (:_rev updated-doc)))
    (is (iso-date? (:updated updated-doc)))
    (is (= (:published new-doc) (:published updated-doc)))
    (is (= (:title updated-doc) "hic sunt dracones"))))

(deftest test-update-feed
  (let [blog-feed (create-feed +test-server+
                               +test-db+
                               {:title "Weblog"
                                :subtitle "Vix Weblog!"
                                :name "blog"
                                :default-slug-format "/{feed-name}/{document-title}"
                                :default-document-type "with-description"})
        blog-feed-updated (update-feed +test-server+
                                       +test-db+
                                       "blog"
                                       {:title "Weblog Feed"
                                        :subtitle "Vix Weblog"
                                        :name "weblog"
                                        :default-slug-format "/{document-title}"
                                        :default-document-type "standard"})]
       
    (is (= (get-feed +test-server+ +test-db+ "blog") blog-feed-updated))
    (is (couchdb-rev? 2 (:_rev blog-feed-updated)))
    (is (iso-date? (:feed-updated blog-feed-updated)))
    (is (= (:created blog-feed) (:created blog-feed-updated)))
    (is (= (:title blog-feed-updated) "Weblog Feed"))
    (is (= (:subtitle blog-feed-updated) "Vix Weblog"))
    (is (= (:name blog-feed-updated) "blog")) ; NB: not updated!
    (is (= (:default-slug-format blog-feed-updated) "/{document-title}"))
    (is (= (:default-document-type blog-feed-updated) "standard"))))

(deftest test-delete-document
  (do
    (create-document +test-server+
                     +test-db+
                     "blog"
                     {:title "foo"
                      :slug "/blog/bar"
                      :content "bar"
                      :draft false}))

  (is (not (nil? (get-document +test-server+ +test-db+ "/blog/bar")))
      "Assure the document exists before it is deleted.")

  (do
    (delete-document +test-server+ +test-db+ "/blog/bar"))
  
  (is (nil? (delete-document +test-server+ +test-db+ "/blog/bar"))
      "Expect nil value if document is deleted twice.")

  (is (nil? (get-document +test-server+ +test-db+ "/blog/bar"))
      "Assure the document is truly removed."))

(deftest test-delete-feed
  (do
    (create-feed +test-server+
                 +test-db+
                 {:title "Weblog"
                  :subtitle "Vix Weblog!"
                  :name "blog"
                  :default-slug-format "/{feed-name}/{document-title}"
                  :default-document-type "with-description"}))
  
  (is (not (nil? (get-feed +test-server+ +test-db+ "blog")))
      "Assure the feed exists before it is deleted.")

  (do
    (delete-feed +test-server+ +test-db+ "blog"))
  
  (is (nil? (delete-feed +test-server+ +test-db+ "blog"))
      "Expect nil value if feed is deleted twice.")

  (is (nil? (get-feed +test-server+ +test-db+ "blog"))
      "Assure the feed is truly removed."))