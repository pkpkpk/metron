(ns metron.webhook-handler
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take! go <!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.reader :refer [read-string]]
            [clojure.string :as string]
            [goog.object]
            [metron.git :as g]
            [metron.docker :as d]
            [metron.logging :as log]
            ["path"]))

(nodejs/enable-util-print!)

(.on js/process "uncaughtException"
     (fn [err origin]
       (log/stderr origin)
       (log/stderr (.-stack err))
       (log/fatal origin)
       (log/fatal (.-stack err))
       (set! (.-exitCode js/process) 99)))

(defn ->clj [o] (js->clj o :keywordize-keys true))

(def S3 (js/require "@aws-sdk/client-s3"))
(def config (read-string (io/slurp (.join path js/__dirname "config.edn"))))
(def Bucket (:Bucket config))
(def region (:region config))
(def client (new (.-S3Client S3) #js{:region region}))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn put-edn [key val]
  (send (new (.-PutObjectCommand S3)
          #js{:Bucket Bucket
              :Key key
              :ContentType "application/edn"
              :Body (if (string? val) val (pr-str val))})))

(defn put-object [key val]
  (send (new (.-PutObjectCommand S3)
          #js{:Bucket Bucket
              :Key key
              :ContentType "text/plain"
              :Body (str val)})))



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
    (log/info "processing" (:full_name repo) " commit " (get head_commit :id))
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

(def ^dynamic *raw-event-string*)

(defn report-results [{:keys [full_name] :as event} [err ok :as res]]
  (let [result-dir (str "webhook_results/" full_name "/" (g/short-sha event))
        {:keys [stdout stderr]} (or err ok)]
    (go
     (<! (put-edn (str result-dir "/result.edn") res))
     (<! (put-edn (str result-dir "/event.json") *raw-event-string*))
     (when stdout
       (<! (put-object (str result-dir "/stdout") stdout)))
     (when stderr
       (<! (put-object (str result-dir "/stderr") stderr)))
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
            (report-results event res)
            (take! (d/process-event event) (partial report-results event)))))
      (catch js/Error err
        (report-results event [{:msg "Unhandled error" :cause (.-stack err)}])))))

(defn handle-event [{:keys [x-github-event ref] :as event}]
  (let []
    (log/info "Handling" x-github-event " event")
    (case x-github-event
      "ping" (handle-ping event)
      "push" (handle-push event)
      (do
        (log/err "Unmatched event" x-github-event)
        (report-results event [{:msg (str "unrecognized event '" x-github-event "'")}])))))

(defn -main [json-path]
  (let [file (io/file json-path)]
    (if-not (.exists file)
      (exit 1 [{:msg "expected path to json file in first argument"}])
      (let [s (io/slurp json-path)]
        (set! *raw-event-string* s)
        (handle-event (parse-event (js/JSON.parse s)))))))

(set! *main-cli-fn* -main)
