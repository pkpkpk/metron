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
            [metron.util :as util :refer [*debug* pp]]))

(nodejs/enable-util-print!)

(defn parse-event [event]
  (let [json (js/JSON.parse event)
        repo (js->clj (.. json -body -repository) :keywordize-keys true)
        repo-ks [:default-branch :full_name :git_url :ssh_url :pushed_at :url]]
    (assoc (select-keys repo repo-ks)
           :query-params (js->clj (.-queryStringParameters json) :keywordize-keys true)
           :x-github-event (goog.object.get (.. json -headers) "x-github-event"))))

(defn put-object [key value]
  (s3/put-object "metronbucket" key value))


(defn exit
  ([code] (exit code nil))
  ([code output]
   (if (zero? code)
     (when output
       (.write (.. js/process -stdout) output))
     (when output
       (.write (.. js/process -stderr) output)))
   (js/process.exit code)))

(defn -main [event]
  (let [{:keys [x-github-event] :as event} (parse-event event)]
    (io/spit "last_event.edn" (pp event))
    ; (when (= x-github-event "ping")
    ;   (take! (put-object "pong.edn" (pp event))
    ;     (fn [[err ok :as res]]
    ;       (if err
    ;         (exit 1 (.-message err))
    ;         (exit 0)))))
    ))

(set! *main-cli-fn* -main)