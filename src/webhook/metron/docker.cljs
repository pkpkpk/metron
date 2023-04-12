(ns metron.docker
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.logging :as log]))

(def path (js/require "path"))

(defn local-dir-path [{:keys [full_name]}]
  (.join path "metron_repos" full_name))

(defn aexec [event cmd]
  (with-promise out
    (take! (proc/aexec cmd {:encoding "utf8" :cwd (local-dir-path event)})
      (fn [[err stdout stderr]]
        (if err
          (put! out [{:err (.-stack err)
                      :msg (.-message err)
                      :stdout stdout
                      :stderr stderr}])
          (put! out [nil {:stdout stdout :stderr stderr}]))))))

(defn build [{:keys [after] :as event}]
  (log/info "building container... " after)
  (aexec event (str "sudo -u ec2-user docker build -t " after " ." )))

(defn run [{:keys [after] :as event}]
  (log/info "running container... " after)
  (aexec event (str "sudo -u ec2-user docker run " after)))

(defn process-event [{:keys [] :as event}]
  (log/info "starting docker")
  (with-promise out
    (if-not (.exists (io/file (local-dir-path event) "Dockerfile"))
      (put! out [{:msg "At this time Metron expects a Dockerfile to run the task"}])
      (take! (build event)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (take! (run event) #(put! out %))))))))