(ns metron.remote
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [cljs-node-io.core :as io :refer [spit slurp file]]
            [cljs-node-io.proc :as proc]
            [clojure.string :as string]
            [metron.aws.ssm :as ssm]
            [metron.bucket :as bkt]
            [metron.git :as g]
            [metron.instance-stack :as instance]
            [metron.keypair :as kp]
            [metron.logging :as log]
            [metron.util :as util :refer [pp pipe1 asset-path]]))

(defn aexec [cmd]
  (with-promise out
    (take! (proc/aexec cmd {:encoding "utf8"})
      (fn [[err stdout stderr]]
        (if err
          (put! out [{:err (.-stack err)
                      :msg (.-message err)
                      :stdout stdout
                      :stderr stderr}])
          (put! out [nil {:stdout stdout :stderr stderr}]))))))

(defn current-branch [] (proc/exec "git rev-parse --abbrev-ref HEAD" {:encoding "utf8"}))

(defn ensure-remote-repo [iid repo-name]
  (ssm/run-script iid (str "sudo -u ec2-user ./bin/create_repo.sh " repo-name)))

(defn metron-remote-url [PublicDnsName repo-path]
  (assert (.isAbsolute (io/file repo-path)) (str "remote repo-path " repo-path " is not absolute"))
  (str "ssh://ec2-user@" PublicDnsName ":" repo-path))

(defn git-prefix [key-path]
  (str "GIT_SSH_COMMAND=\"ssh -i " key-path "\""))

(defn git-push [key-path url branch]
  (log/info "Pushing " branch " to instance")
  (aexec (str (git-prefix key-path) " git push " url " " branch)))

(defn sync-bare-to-non-bare [iid repo-name branch]
  (log/info "syncing remote bare repo to usable worktree")
  (ssm/run-script iid (str "sudo -u ec2-user ./bin/sync_bare_to_non_bare.sh " repo-name " " branch)))

(defn ?validate-cwd []
  (log/info "Validating" (.cwd js/process))
  (let [errs (atom [])]
    (when-not (.exists (io/file (.cwd js/process) "Dockerfile"))
      (swap! errs conj "missing Dockerfile"))
    (when-not (.exists (io/file (.cwd js/process) ".git"))
      (swap! errs conj "is not a git repo"))
    (not-empty @errs)))

(defn sync-repos
  [{:keys [InstanceId PublicDnsName] :as outputs}]
  (with-promise out
    (let [cwd (io/file (.cwd js/process))
          repo-name (.getName cwd)]
      (take! (ensure-remote-repo InstanceId repo-name)
        (fn [[err {:keys [StandardOutputContent StandardErrorContent] :as ok} :as res]]
          (if err
            (put! out res)
            (let [_(log/info (string/trim-newline StandardErrorContent))
                  key-path (.getPath (kp/?existing-file))
                  bare-repo-path (string/trim-newline StandardOutputContent)
                  remote-url (metron-remote-url PublicDnsName bare-repo-path)
                  branch (current-branch)]
              (take! (git-push key-path remote-url branch)
                (fn [[err ok :as res]]
                  (if err
                    (put! out res)
                    (take! (sync-bare-to-non-bare InstanceId repo-name branch)
                      (fn [[err ok :as res]]
                        (put! out res)))))))))))))

(defn push [opts]
  (with-promise out
    (take! (instance/wait-for-ok)
      (fn [[err {:keys [InstanceId PublicDnsName] :as outputs} :as res]]
        (if err
          (put! out res)
          (if-let [err (?validate-cwd)]
            (put! out [err])
            (pipe1 (sync-repos outputs) out)))))))