(ns metron.aws.s3
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.aws :refer [AWS]]))

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

(defn put-object
  ([bucket-name key obj]
   (send (new (.-PutObjectCommand S3) #js{:Bucket bucket-name :Key key :Body obj})))
  ([bucket-name key obj metadata]
   (send (new (.-PutObjectCommand S3) #js{:Bucket bucket-name
                                          :Key key
                                          :Body obj
                                          :Metadata (clj->js metadata)}))))

(defn get-object [bucket-name key]
  (send (new (.-GetObjectCommand S3) #js{:Bucket bucket-name :Key key})))

(defn delete-object [bucket-name key]
  (send (new (.-DeleteObjectCommand S3) #js{:Bucket bucket-name :Key key})))

(defn wait-for-bucket-exists [bucket-name]
  (edn-res-chan (.waitUntilBucketExists S3 #js{:Bucket bucket-name})))

(defn wait-for-object-exists [bucket-name key]
  (edn-res-chan (.waitUntilObjectExists S3 #js{:Bucket bucket-name :Key key})))