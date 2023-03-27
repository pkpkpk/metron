(ns metron.webhook-handler
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.pprint :refer [pprint]]
            [cljs.reader :refer [read-string]]
            [clojure.string :as string]
            [goog.object]
            [metron.aws.s3 :as s3]
            [metron.git :as g]
            [metron.docker :as d]
            [metron.logging :as log]
            [metron.util :as util]))

(nodejs/enable-util-print!)

(defn parse-event [event]
  (let [json (js/JSON.parse event)
        repo (js->clj (.. json -body -repository) :keywordize-keys true)
        head_commit (some-> (.. json -body -head_commit) (js->clj :keywordize-keys true))
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
           :before (.. json -body -before)
           :after (.. json -body -after)
           :ref (.. json -body -ref)
           :head_commit head_commit
           :query-params (js->clj (.-queryStringParameters json) :keywordize-keys true)
           :x-github-event (goog.object.get (.. json -headers) "x-github-event"))))

(defn exit
  ([code] (exit code nil))
  ([code output]
   (when output
     (if (zero? code)
       (.write (.. js/process -stdout) (pr-str output))
       (.write (.. js/process -stderr) (pr-str output))))
   (js/process.exit code)))

(def ^:dynamic *bucket*)

(defn report-results [[err ok :as res]]
  (io/spit "result.edn" res)
  (take! (s3/put-object *bucket* "result.edn" res)
    (fn [_]
      (if err
        (exit 1 (.-message err))
        (exit 0)))))

(defn handle-ping [event]
  (take! (s3/put-object *bucket* "pong.edn" event)
    (fn [[err ok :as res]]
      (if err
        (exit 1 (hash-map :msg (str "handle-ping put-object error: " (.-message err)) :stack (.-stack err)))
        (exit 0)))))

(defn handle-push [{:as event}]
  (when (= (:ref event) "refs/heads/metron")
    (try
      (take! (g/fetch-event event)
        (fn [[err ok :as res]]
          (if err
            (report-results res)
            (take! (d/process-event event) report-results))))
      (catch js/Error err
        (report-results [{:msg "Uncaught error"
                          :cause err}])))))

(defn handle-event [{:keys [x-github-event ref] :as event}]
  (let []
    (io/spit "event.edn" event)
    (case x-github-event
      "ping" (handle-ping event)
      "push" (handle-push event)
      (report-results [{:msg (str "unrecognized event '" x-github-event "'")}]))))

(defn -main
  [raw-event]
  (if (and (nil? (goog.object.get (.-env js/process) "AWS_REGION"))
           (nil? (goog.object.get (.-env js/process) "AWS_PROFILE")))
    (exit 1 [{:msg "please run with AWS_REGION or AWS_PROFILE=metron"}])
    (let [{:keys [Bucket] :as cfg} (read-string (io/slurp "config.edn"))]
      (if (nil? Bucket)
        (exit 1 [{:msg "missing Bucket in config.edn"}])
        (if (nil? raw-event)
          (exit 1 [{:msg "expected json string in first arg"}])
          (do
            (set! *bucket* Bucket)
            (handle-event (parse-event raw-event))))))))

(.on js/process "uncaughtException"
  (fn [err origin]
    (log/err (pr-str {:err err :origin origin}))
    (set! (.-exitCode js/process) 1)))

(set! *main-cli-fn* -main)
