(ns metron.server.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [goog.object]
            [metron.aws.s3 :as s3]
            [metron.aws.ec2 :as ec2]
            [metron.git :as g]
            [metron.util :as util :refer [*debug* pp]]))

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

(defn put-object [key value]
  (s3/put-object "metronbucket" key value))

(defn exit
  ([code] (exit code nil))
  ([code output]
   (if (zero? code)
     (when output
       (.write (.. js/process -stdout) (pr-str output)))
     (when output
       (.write (.. js/process -stderr) (pr-str output))))
   (js/process.exit code)))

(defn report-results [[err ok :as res]]
  (io/spit "last-result.edn" (pp res))
  (take! (put-object "results.edn" (pp res))
    (fn [_]
      (if err
        (exit 1 (.-message err))
        (exit 0)))))

(defn handle-ping [event]
  (take! (put-object "pong.edn" (pp event))
    (fn [[err ok :as res]]
      (if err
        (exit 1 (.-message err))
        (exit 0)))))

(defn handle-push [event]
  (try
    (take! (g/fetch-event event) report-results)
    (catch js/Error err
      (report-results [{:msg "Uncaught error"
                        :cause err}]))))

(defn -main
  [raw-event]
  (io/spit "last_event.json" raw-event)
  (take! (put-object "last_event.json" raw-event)
    (fn [_]
      (let [{:keys [x-github-event] :as event} (parse-event raw-event)]
        (case x-github-event
          "ping" (handle-ping event)
          "push" (handle-push event)
          (report-results [{:msg (str "unrecognized event '" x-github-event "'")}]))))))

(.on js/process "uncaughtException"
  (fn [err origin]
    (io/spit "uncaughtException.edn" {:err err :origin origin})
    (put-object "uncaughtException.edn" {:err err :origin origin})))

(set! *main-cli-fn* -main)