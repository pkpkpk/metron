(ns metron.bucket
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take! go-loop >! <!]]
            [clojure.string :as string]
            [cljs.reader :refer [read-string]]
            [cljs-node-io.core :as io]
            [metron.aws.iam :as iam]
            [metron.aws.s3 :as s3]
            [metron.logging :as log]
            [metron.util :refer [pipe1 ->clj]]))

(def ^:dynamic *bucket-name*)

(defn derive-bucket-name []
  (with-promise out
    (take! (iam/get-user)
      (fn [[err {{UserId :UserId} :User} :as res]]
        (if err
          (put! out res)
          (let [bucket-name (str "metronbucket" (string/lower-case UserId))]
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
                    (log/info "Creating bucket " bucket-name)
                    (take! (s3/create-bucket bucket-name region)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out res)
                          (do
                            (log/info "waiting for bucket...")
                            (take! (s3/wait-for-bucket-exists bucket-name)
                              (fn [[err ok :as res]]
                                (if err
                                  (put! out res)
                                  (put! out [nil bucket-name]))))))))))))))))))

(defn get-object [key]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/get-object bucket-name key) out))))))

(defn read-object "if the object is recognized as json or edn, returns read as clj"
  [key]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (take! (s3/get-object bucket-name key)
            (fn [[err {:keys [ContentType Body] :as ok} :as res]]
              (if err
                (put! out res)
                (let [buf (.read (:Body ok))]
                  (cond
                    (or (string/ends-with? key "json")
                        (= ContentType "application/json"))
                    (try
                      (put! out [nil (->clj (js/JSON.parse (.toString buf "utf8")))])
                      (catch js/Error e
                        (put! out [{:msg (str "error parsing json object " key) :cause (.-stack e)}])))

                    (or (string/ends-with? key "edn")
                        (= ContentType "application/edn"))
                    (try
                      (put! out [nil (read-string (.toString buf "utf8"))])
                      (catch js/Error e
                        (put! out [{:msg (str "error reading edn object " key) :cause (.-stack e)}])))

                    true
                    (condp = ContentType
                      "application/octet-stream" (put! out [nil buf])
                      "plain/text" (put! out [nil (.toString buf "utf8")])
                      (do
                        (log/warn "Unrecognized ContentType" ContentType " for s3 key " key)
                        (put! out [nil buf])))))))))))))

(defn put-object [key value]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (let [val (if (or (string? value) (.isBuffer js/Buffer value))
                      value
                      (pr-str value))]
            (pipe1 (s3/put-object bucket-name key val) out)))))))

(defn delete-object [key]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/delete-object bucket-name key) out))))))

(defn upload-file
  ([src-path]
   (upload-file src-path nil))
  ([src-path folder]
   (with-promise out
     (take! (io/aslurp src-path)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (let [dst-path (cond->> (.getName (io/file src-path))
                            folder (str folder "/"))]
             (pipe1 (put-object dst-path ok) out))))))))

(defn upload-files
  ([files] (upload-files files nil))
  ([files folder]
   (with-promise out
     (go-loop [files files]
       (if-let [file (first files)]
         (let [[err ok :as res] (<! (upload-file file folder))]
           (if (some? err)
             (put! out res)
             (recur (rest files))))
         (put! out [nil]))))))

(defn wait-for-object [key]
  (with-promise out
    (take! (get-bucket-name)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
          (pipe1 (s3/wait-for-object-exists bucket-name key) out))))))

#!==============================================================================

(defn delete-result [] (delete-object "result.edn"))

(defn read-result [] (read-object "result.edn"))

(defn wait-for-result [] ;=> [?waiter-error/read-object-error [?reserr ?resok]]
  (with-promise out
    (take! (wait-for-object "result.edn")
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (pipe1 (read-object "result.edn") out))))))


