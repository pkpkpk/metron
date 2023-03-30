(ns metron.webhook-handler
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.pprint :refer [pprint]]
            [cljs.reader :refer [read-string]]
            [clojure.string :as string]
            [goog.object]
            [metron.git :as g]
            [metron.docker :as d]
            [metron.logging :as log]
            [metron.util :as util :refer [->clj]]
            ["path"]))

(nodejs/enable-util-print!)

(.on js/process "uncaughtException"
     (fn [err origin]
       (log/fatal origin)
       (log/fatal (.-stack err))
       (set! (.-exitCode js/process) 99)))

(def put-edn
  (let [S3 (js/require "@aws-sdk/client-s3")
        {:keys [Bucket region]} (read-string (io/slurp (.join path js/__dirname "config.edn")))
        client (new (.-S3Client S3) #js{:region region})
        send (fn [cmd] (edn-res-chan (.send client cmd)))]
    (fn [key val]
      (send (new (.-PutObjectCommand S3)
                 #js{:Bucket Bucket
                     :Key key
                     :ContentType "application/edn"
                     :Body (if (string? val) val (pr-str val))})))))

(defn parse-event [json]
  (let [repo (some-> (.. json -body) (.. -repository) ->clj)
        head_commit (some->  (.. json -body) (.. -head_commit) ->clj)
        query-params (some-> (.. json -queryStringParameters) ->clj)
        repo-ks [:default_branch
                 :full_name
                 :git_url
                 :ssh_url
                 :pushed_at
                 :url
                 :name
                 :master_branch
                 :default_branch
                 :pushed_at]]
    (assoc (select-keys repo repo-ks)
           :before (some-> (.. json -body) (.. -before))
           :after (some-> (.. json -body) (.. -after))
           :ref (some-> (.. json -body) (.. -ref))
           :head_commit head_commit
           :query-params query-params
           :x-github-event (goog.object.get (.. json -headers) "x-github-event"))))

(defn exit
  ([code] (exit code nil))
  ([code output]
   (when output
     (log/stdout (pr-str output))
     (when-not (zero? code)
       (log/err output)))
   (js/process.exit code)))

(defn report-results [[err ok :as res]]
  (take! (put-edn "result.edn" res)
    (fn [[s3-err]]
      (when s3-err (log/warn s3-err))
      (if err
        (exit 1 res)
        (exit 0 res)))))

(defn handle-ping [event]
  (take! (put-edn "pong.edn" event)
    (fn [[err ok :as res]]
      (if err
        (exit 1 [{:msg "error writing ping handling to bucket" :cause (.-stack err)}])
        (exit 0 [nil {:msg "pong.edn has successfully been put in bucket"}])))))

(defn handle-push [{:as event}]
  (when (= (:ref event) "refs/heads/metron")
    (try
      (take! (g/fetch-event event)
        (fn [[err ok :as res]]
          (if err
            (report-results res)
            (take! (d/process-event event) report-results))))
      (catch js/Error err
        (report-results [{:msg "Unhandled error" :cause (.-stack err)}])))))

(defn handle-event [{:keys [x-github-event ref] :as event}]
  (let []
    (log/info "Handling" x-github-event " event")
    (case x-github-event
      "ping" (handle-ping event)
      "push" (handle-push event)
      (report-results [{:msg (str "unrecognized event '" x-github-event "'")}]))))

(defn -main [json-path]
  (let [file (io/file json-path)]
    (if-not (.exists file)
      (exit 1 [{:msg "expected path to json file in first argument"}])
      (let [json (js/JSON.parse (io/slurp json-path))]
        (handle-event (parse-event json))))))

(set! *main-cli-fn* -main)
