(ns metron.bucket
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [metron.aws.s3 :as s3]))

(def ^:dynamic *bucket-name* "metronbucket")

(defn ensure-bucket [args]
  (with-promise out
    (take! (s3/head-bucket *bucket-name*)
      (fn [[err ok :as res]]
        (if (nil? err)
          (put! out [nil])
          (if-not (string/includes? (str err) "NotFound")
            (put! out [{:msg "unrecognized s3/head-bucket error"
                        :cause err}])
            (take! (s3/create-bucket *bucket-name* (:region args))
              (fn [[err ok :as res]]
                (put! out res)))))))))