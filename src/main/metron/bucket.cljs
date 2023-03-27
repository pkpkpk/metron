(ns metron.bucket
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take! go-loop >! <!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.iam :as iam]
            [metron.aws.s3 :as s3]
            [metron.util :refer [pipe1 info]]))

(def ^:dynamic *bucket-name*)

(defn derive-bucket-name []
  (with-promise out
    (take! (iam/get-user)
      (fn [[err {{UserId :UserId} :User} :as res]]
        (if err
          (put! out res)
          (let [bucket-name (str "metronbucket" UserId)]
            (set! *bucket-name* bucket-name)
            (put! out [nil bucket-name])))))))

(defn get-bucket-name []
  (with-promise out
    (if (some? *bucket-name*)
      (put! out [nil *bucket-name*])
      (pipe1 (derive-bucket-name) out))))

(defn ensure-bucket [{region :region :as opts}]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (take! (s3/head-bucket bucket-name)
            (fn [[err ok :as res]]
              (if (nil? err)
                (put! out [nil bucket-name])
                (if-not (string/includes? (str err) "NotFound")
                  (put! out [err])
                  (do
                    (info "Creating bucket " bucket-name)
                    (take! (s3/create-bucket bucket-name region)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out res)
                          (do
                            (info "waiting for bucket...")
                            (take! (s3/wait-for-bucket-exists bucket-name)
                              (fn [[err ok :as res]]
                                (if err
                                  (put! out res)
                                  (put! out [nil bucket-name]))))))))))))))))))

(defn put-object [key value]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/put-object bucket-name key value) out))))))

(defn upload-file
  ([src-path]
   (upload-file src-path (.getName (io/file src-path))))
  ([src-path dst-path]
   (with-promise out
     (take! (io/aslurp src-path)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (pipe1 (put-object dst-path ok) out)))))))

(defn upload-files [files]
  (with-promise out
    (go-loop [files files]
      (if-let [file (first files)]
        (let [[err ok :as res] (<! (upload-file file))]
          (if (some? err)
            (put! out res)
            (recur (rest files))))
        (put! out [nil])))))

(defn delete-pong []
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/delete-object bucket-name "pong.edn") out))))))

(defn wait-for-pong []
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/wait-for-object-exists bucket-name "pong.edn") out))))))

(defn get-result []
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/get-object bucket-name "result.edn") out))))))

(defn delete-result []
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/delete-object bucket-name "result.edn") out))))))

(defn wait-for-result []
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/wait-for-object-exists bucket-name "result.edn") out))))))
