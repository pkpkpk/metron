(ns metron.bucket
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [metron.aws.s3 :as s3]
            [metron.util :refer [pipe1]]))

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
              (println "Creating metron bucket")
              (pipe1 (s3/create-bucket *bucket-name* (:region args)) out))))))))

(defn ensure-no-pong []
  (with-promise out
    (take! (s3/delete-object *bucket-name* "PONG.json")
      (fn [[err :as res]] ; "NoSuchKey"
        (put! out [nil])))))

(defn wait-for-pong []
  (s3/wait-for-exists *bucket-name* "PONG.json"))