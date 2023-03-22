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
            [metron.bucket :as bkt]
            [metron.git :as g]
            [metron.docker :as d]
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

(defn exit
  ([code] (exit code nil))
  ([code output]
   (when output
     (if (zero? code)
       (.write (.. js/process -stdout) (pr-str output))
       (.write (.. js/process -stderr) (pr-str output))))
   (js/process.exit code)))

(defn report-results [[err ok :as res]]
  (io/spit "result.edn" (pp res))
  (take! (bkt/put-object "result.edn" (pp res))
    (fn [_]
      (if err
        (exit 1 (.-message err))
        (exit 0)))))

(defn handle-ping [event]
  (take! (bkt/put-object "pong.edn" (pp event))
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

(defn get-config []
  (read-string (io/slurp "config.edn")))

(defn setup-bucket []
  (let [{:keys [bucket-name]} (get-config)]
    (assert (string? bucket-name))
    (set! bkt/*bucket-name* bucket-name)))

(defn -main
  [raw-event]
  (if (nil? (goog.object.get (.-env js/process) "AWS_REGION"))
    (exit 1 [{:msg "please run with AWS_REGION set"}])
    (take! (do
             (io/spit "event.json" raw-event)
             (setup-bucket)
             (bkt/put-object "event.json" raw-event))
           (fn [_]
             (let [{:keys [x-github-event ref] :as event} (parse-event raw-event)]
               (io/spit "event.edn" (pp event))
               (case x-github-event
                 "ping" (handle-ping event)
                 "push" (handle-push event)
                 (report-results [{:msg (str "unrecognized event '" x-github-event "'")}])))))))

(.on js/process "uncaughtException"
  (fn [err origin]
    (println "uncaughtException:" {:err err :origin origin})
    (io/spit "uncaughtException.edn" {:err err :origin origin})
    (bkt/put-object "uncaughtException.edn" (pp {:err err :origin origin}))))

(set! *main-cli-fn* -main)
