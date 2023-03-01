(ns metron.git
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.util :refer [*debug* dbg pipe1] :as util]))

{:default_branch "main",
 :full_name "pkpkpk/testrepo",
 :pushed_at "2023-02-02T18:48:27Z",
 :query-params {:branch "metron"},
 :url "https://api.github.com/repos/pkpkpk/testrepo",
 :git_url "git://github.com/pkpkpk/testrepo.git",
 :ssh_url "git@github.com:pkpkpk/testrepo.git",
 :x-github-event "ping"}

(def path (js/require "path"))

(defn aclone-repo [{:keys [full_name ssh_url] :as opts}]
  (println "cloning repo:" full_name)
  (proc/aexec (str "git clone --depth 1 " ssh_url " " (.join path "metron_repository" full_name))
             {:encoding "utf8"
              :env {"GIT_SSH_COMMAND" "ssh -i deploykey"}
              }))

(defn repo-exists? [{:keys [full_name ssh_url] :as opts}]
  (.exists (io/file "metron_repository" full_name)))

(defn ensure-repo-dir []
  (let [f (io/file "metron_repository")]
    (when-not (.exists f)
      (.mkdir f))))

(defn ensure-repo [{:keys [full_name ssh_url] :as opts}]
  (with-promise out
    (let [repo (io/file "metron_repository" full_name)]
      (ensure-repo-dir)
      (if (.exists repo)
        (put! out [nil])
        (take! (aclone-repo opts)
          (fn [[err ok :as res]]
            (println "aclone:" res)
            (put! out res)))))))

; (defn fetch-branch [])

