(ns metron.bucket
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [metron.aws.s3 :as s3]
            [metron.util :refer [pipe1 info]]))

(def ^:dynamic *bucket-name* "metronbucket")

;;TODO setup stack to import this
(defn ensure-bucket [args]
  (with-promise out
    (take! (s3/head-bucket *bucket-name*)
      (fn [[err ok :as res]]
        (if (nil? err)
          (put! out [nil])
          (if-not (string/includes? (str err) "NotFound")
            (put! out [{:msg "unrecognized s3/head-bucket error"
                        :cause err}])
            (do
              (info "Creating metron bucket")
              (take! (s3/create-bucket *bucket-name* (:region args))
                (fn [[err ok :as res]]
                  (if err
                    (put! out res)
                    (do
                      (info "waiting for bucket...")
                      (pipe1 (s3/wait-for-bucket-exists *bucket-name*) out))))))))))))

(defn delete-pong []
  (s3/delete-object *bucket-name* "pong.edn"))

(defn wait-for-pong []
  (s3/wait-for-object-exists *bucket-name* "pong.edn"))

(defn get-result []
  (s3/get-object *bucket-name* "result.edn"))

(defn delete-result []
  (s3/delete-object *bucket-name* "result.edn"))

(defn wait-for-result []
  (s3/wait-for-object-exists *bucket-name* "result.edn"))

(defn put-object [key value]
  (s3/put-object *bucket-name* key value))