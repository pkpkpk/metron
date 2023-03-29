(ns metron.aws.s3
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]))

(def S3 (js/require "@aws-sdk/client-s3"))
(def client (new (.-S3Client S3)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn create-bucket
  "https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/clients/client-s3/interfaces/createbucketcommandinput.html"
  ([bucket-or-opts]
   (let [opts (if (string? bucket-or-opts)
                #js{"Bucket" bucket-or-opts}
                (clj->js bucket-or-opts))]
     (send (new (.-CreateBucketCommand S3) opts))))
  ([bucket-name region]
   (if (= region "us-east-1")
     (create-bucket bucket-name)
     (create-bucket #js{"Bucket" bucket-name
                        "CreateBucketConfiguration" #js{"LocationConstraint" region}}))))

(defn delete-bucket [bucket-name]
  (send (new (.-DeleteBucketCommand S3) #js{"Bucket" bucket-name})))

(defn head-bucket [bucket-name]
  (send (new (.-HeadBucketCommand S3) #js{"Bucket" bucket-name})))

(defn head-object [bucket-name key]
  (send (new (.-HeadObjectCommand S3) #js{"Bucket" bucket-name "Key" key})))

(defn put-object
  ([bucket-name key obj]
   (put-object bucket-name key obj nil))
  ([bucket-name key obj metadata]
   (assert (or (string? obj) (.isBuffer js/Buffer obj)))
   (let [ct (if (string/ends-with? key ".json")
              "application/json"
              (if (string/ends-with? key ".edn")
                "application/edn"
                (if (.isBuffer js/Buffer obj)
                  "application/octet-stream"
                  "text/plain")))
         o #js{:Bucket bucket-name
               :Key key
               :Body obj
               :ContentType ct}
         _(when metadata (set! (.-Metadata o) (clj->js metadata)))]
     (send (new (.-PutObjectCommand S3) o)))))

(defn get-object [bucket-name key]
  (send (new (.-GetObjectCommand S3) #js{:Bucket bucket-name :Key key})))

(defn delete-object [bucket-name key]
  (send (new (.-DeleteObjectCommand S3) #js{:Bucket bucket-name :Key key})))

#!==============================================================================

(def ^:dynamic *poll-delay* 5)
(def ^:dynamic *max-wait-time* 500)

(defn p->res [p]
  (with-promise out
    (.then p
           #(put! out (cond-> [nil] (some? %) (conj (cljs.core/js->clj % :keywordize-keys true))))
           #(put! out [(cljs.core/js->clj % :keywordize-keys true)]))))

(defn wait-for-bucket-exists [bucket-name]
  (p->res (.waitUntilBucketExists S3 #js{:client client
                                         :maxDelay *poll-delay*
                                         :maxWaitTime *max-wait-time*}
                                     #js{:Bucket bucket-name})))

(defn wait-for-object-exists [bucket-name key]
  (p->res (.waitUntilObjectExists S3
                                  #js{:client client
                                      :maxDelay *poll-delay*
                                      :maxWaitTime *max-wait-time*}
                                  #js{:Bucket bucket-name
                                      :Key key})))