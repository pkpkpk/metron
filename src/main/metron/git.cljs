(ns metron.git
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.util :refer [*debug* dbg pipe1] :as util]))

(def path (js/require "path"))

(defn local-dir-path [{:keys [full_name]}]
  (.join path "metron_repository" full_name))

(defn repo-exists? [event]
  (.exists (io/file (local-dir-path event))))

(defn clone-repo [{:keys [full_name ssh_url] :as event}]
  (proc/aexec (str "git clone --depth 1 " ssh_url " " (local-dir-path event))
             {:encoding "utf8"
              :env {"GIT_SSH_COMMAND" "ssh -i deploykey"}}))

(defn fetch-branch [{:keys [full_name ssh_url] :as event}]
  (proc/aexec (str "git fetch --depth 1 origin metron")
              {:encoding "utf8"
               :cwd (local-dir-path event)
               :env {"GIT_SSH_COMMAND" "ssh -i deploykey"}}))

(defn current-sha [{:keys [after] :as event}]
  (proc/aexec (str "git rev-parse HEAD")
              {:encoding "utf8"
               :cwd (local-dir-path event)}))

(defn ensure-metron-checkout [{:keys [full_name ssh_url] :as event}]
  (with-promise out
    (take! (proc/aexec "git status" {:encoding "utf8" :cwd (local-dir-path event)})
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if (string/starts-with? ok "On branch metron")
            (put! out [nil])
            (pipe1 (proc/aexec "git checkout metron" {:encoding "utf8" :cwd (local-dir-path event)})
              out)))))))

(defn ensure-repo [{:keys [full_name ssh_url] :as opts}]
  (with-promise out
    (if (repo-exists? opts)
      (put! out [nil])
      (let [f (io/file "metron_repository")]
        (when-not (.exists f)
          (.mkdir f))
        (pipe1 (clone-repo opts) out)))))

(defn sha-matches?
  [{:keys [ref full_name after] :as event}]
  (with-promise out
    (take! (current-sha event)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if (= after (string/trim ok))
            (put! out [nil])
            (put! out [{:msg "sha does not match after fetch?!"}])))))))

{:default_branch "main",
 :full_name "pkpkpk/testrepo",
 :pushed_at "2023-02-02T18:48:27Z",
 :query-params {:branch "metron"},
 :url "https://api.github.com/repos/pkpkpk/testrepo",
 :git_url "git://github.com/pkpkpk/testrepo.git",
 :ssh_url "git@github.com:pkpkpk/testrepo.git",
 :x-github-event "ping"}

(defn fetch-event
  [{:as event}]
  (with-promise out
    (take! (ensure-repo event)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (fetch-branch event)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (ensure-metron-checkout event)
                  (fn [[err ok :as res]]
                    (if err
                      (put! out res)
                      (pipe1 (sha-matches? event) out))))))))))))



