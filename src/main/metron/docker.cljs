(ns metron.docker
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.util :refer [*debug* info dbg pipe1] :as util]))

(def path (js/require "path"))

(defn local-dir-path [{:keys [full_name]}]
  (.join path "metron_repos" full_name))

(defn build [{:keys [after] :as event}]
  (info "building container... " after)
  (proc/aexec (str "sudo -u ec2-user docker build -t " after " ." )
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn run [{:keys [after] :as event}]
  (info "running container... " after)
  (proc/aexec (str "sudo -u ec2-user docker run " after)
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn process-event [{:keys [] :as event}]
  (info "starting docker")
  (with-promise out
    (if-not (.exists (io/file (local-dir-path event) "Dockerfile"))
      (put! out [{:msg "At this time Metron expects a Dockerfile to run the task"}])
      (take! (build event)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (pipe1 (run event) out)))))))