(ns metron.git
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.logging :as log]))

(def path (js/require "path"))

(defn local-dir-path [{:keys [full_name]}]
  (.join path "webhook_repos" full_name))

(defn short-sha [{:as event}]
  (let [cwd (local-dir-path event)]
    (-> (proc/exec "sudo -u ec2-user git rev-parse --short HEAD" {:encoding "utf8" :cwd cwd})
      (string/trim-newline ))))

(defn clone-repo [{:keys [ssh_url] :as event}]
  (log/info "cloning" ssh_url)
  (proc/aexec (str "sudo -u ec2-user git clone --depth 1 " ssh_url " " (local-dir-path event))
             {:encoding "utf8"}))

(defn fetch-branch [{:as event}]
  (proc/aexec (str "sudo -u ec2-user git fetch origin metron")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn create-metron-branch [{:as event}]
  (proc/aexec (str "sudo -u ec2-user git checkout -b metron")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn pull-metron-branch [{:as event}]
  (log/info "Pulling latest branch...")
  (proc/aexec (str "sudo -u ec2-user git pull origin metron")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn checkout-metron-branch [{:as event}]
  (proc/aexec (str "sudo -u ec2-user git checkout metron")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn current-sha [{:as event}]
  (proc/aexec (str "sudo -u ec2-user git rev-parse HEAD")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn ensure-metron-branch [{:as event}]
  (with-promise out
    (take! (checkout-metron-branch event)
      (fn [[err :as res]]
        (if (nil? err)
          (take! (pull-metron-branch event) #(put! out %))
          (if-not (string/includes? (.-message err) "did not match any file(s) known to git")
            (put! out res)
            (take! (create-metron-branch event)
              (fn [[err ok :as res]]
                (if err
                  (put! out res)
                  (take! (pull-metron-branch event)
                    (fn [[err :as res]]
                      (if err
                        (put! out res)
                        (put! out [nil event])))))))))))))

(defn ensure-repo [{:as event}]
  (with-promise out
    (let [repo-path (local-dir-path event)]
      (if (.exists (io/file repo-path))
        (put! out [nil (assoc event :repo-path repo-path)])
        (take! (clone-repo event)
          (fn [[err :as res]]
            (if err
              (put! out res)
              (put! out [nil (assoc event :repo-path repo-path)]))))))))

(defn sha-matches?
  [{:keys [after] :as event}]
  (with-promise out
    (take! (current-sha event)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if (= after (string/trim ok))
            (do
              (log/info "branch is up to date")
              (put! out [nil event]))
            (do
              (log/err "current sha do not match sha from event")
              (put! out [{:msg "sha does not match after fetch?!"}]))))))))

(defn fetch-event
  [{:as event}]
  (with-promise out
    (take! (ensure-repo event)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (ensure-metron-branch ok)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (sha-matches? event)
                  (fn [[err :as res]]
                    (if err
                      (put! out res)
                      (put! out [nil ok]))))))))))))

