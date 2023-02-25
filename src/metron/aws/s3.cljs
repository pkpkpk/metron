(ns metron.aws.s3
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.aws :refer [AWS]]))

(def S3 (new (.-S3 AWS) #js{:apiVersion "2006-03-01"}))

(defn create-bucket
  ([bucket-name]
   (edn-res-chan (.createBucket S3 #js{"Bucket" bucket-name})))
  ([bucket-name region]
   (if (= region "us-east-1")
     (create-bucket bucket-name)
     (edn-res-chan (.createBucket S3 #js{"Bucket" bucket-name
                                         "CreateBucketConfiguration" #js{"LocationConstraint" region}})))))



(defn delete-bucket [bucket-name]
  (edn-res-chan (.deleteBucket S3 #js{"Bucket" bucket-name})))

(defn head-bucket [bucket-name]
  (edn-res-chan (.headBucket S3 #js{"Bucket" bucket-name})))

(defn put-object
  ([bucket-name key obj]
   (put-object bucket-name key obj nil))
  ([bucket-name key obj metadata]
   (assert (string? obj))
   (edn-res-chan (.putObject S3 #js{:Bucket bucket-name
                                    :Key key
                                    :Body obj
                                    :Metadata (clj->js metadata)}))))

(defn get-object
  [bucket-name key]
  (edn-res-chan (.getObject S3 #js{:Bucket bucket-name :Key key})))

(defn delete-object
  [bucket-name key]
  (edn-res-chan (.deleteObject S3 #js{:Bucket bucket-name :Key key})))

(defn wait-for-exists
  [bucket-name key]
  (edn-res-chan (.waitFor S3 "objectExists" #js{:Bucket bucket-name :Key key})))