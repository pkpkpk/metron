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

(defn short-sha []
  (string/trim-newline (proc/exec "git rev-parse --short HEAD" {:encoding "utf8"})))

(defn ensure-bare-repo [iid repo-name]
  (log/info "Ensuring bare-repo exists on instance" iid)
  (ssm/run-script iid (str "sudo -u ec2-user ./bin/ensure_bare_repo.sh " repo-name)))

(defn metron-remote-url [PublicDnsName repo-path]
  (assert (.isAbsolute (io/file repo-path)) (str "remote repo-path " repo-path " is not absolute"))
  (str "ssh://ec2-user@" PublicDnsName ":" repo-path))

(defn git-prefix [key-path]
  (str "GIT_SSH_COMMAND=\"ssh -i " key-path "\""))

(defn git-push [key-path url branch]
  (log/info "Pushing branch" branch "to instance")
  (aexec (str (git-prefix key-path) " git push " url " " branch)))

(defn sync-bare-to-non-bare [iid repo-name branch short-sha]
  (log/info "syncing remote bare repo to usable worktree")
  (let [cmd (str "sudo -u ec2-user ./bin/sync_bare_to_non_bare.sh " repo-name " " branch " " short-sha)]
    (ssm/run-script iid cmd)))

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
      (take! (ensure-bare-repo InstanceId repo-name)
        (fn [[err {:keys [StandardOutputContent StandardErrorContent] :as ok} :as res]]
          (if err
            (put! out res)
            (let [;_(log/info "<ec2-user ensure_bare_repo.sh>:" (string/trim-newline StandardErrorContent))
                  key-path (.getPath (kp/?existing-file))
                  bare-repo-path (string/trim-newline StandardOutputContent)
                  remote-url (metron-remote-url PublicDnsName bare-repo-path)
                  branch (string/trim-newline (current-branch))
                  short-sha (short-sha)]
              (take! (git-push key-path remote-url branch)
                (fn [[err ok :as res]]
                  (if err
                    (put! out res)
                    (take! (sync-bare-to-non-bare InstanceId repo-name branch short-sha)
                      (fn [[err ok :as res]]
                        (put! out res)))))))))))))

(defn docker-build [iid non-bare-path tag]
  (let [cmd (str "sudo -u ec2-user docker build -t " tag " ." )]
    (ssm/run-script iid cmd {:cwd non-bare-path})))

(defn docker-run [iid non-bare-path tag]
  (let [cmd (str "sudo -u ec2-user docker run " tag)]
    (log/info "running container...")
    (ssm/run-script iid cmd {:cwd non-bare-path})))

(defn push [opts]
  (with-promise out
    (take! (instance/wait-for-ok)
      (fn [[err {:keys [InstanceId PublicDnsName] :as outputs} :as res]]
        (if err
          (put! out res)
          (if-let [errors (?validate-cwd)]
            (put! out [{:errors errors :msg "Invalid local repository"}]) ;;TODO this doesnt report well
            (take! (sync-repos outputs)
              (fn [[err {:keys [StandardOutputContent]} :as res]]
                (if err
                  (put! out res)
                  (let [non-bare-path (string/trim-newline StandardOutputContent)
                        _(assert (string/starts-with? non-bare-path "/home/ec2-user"))
                        tag (short-sha)]
                    (take! (docker-build InstanceId non-bare-path tag)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out res)
                          (take! (docker-run InstanceId non-bare-path tag)
                            (fn [[err {:keys [StandardOutputContent StandardErrorContent]} :as res]]
                              (if err
                                (put! out res)
                                (let [stderr-lines (string/split-lines StandardErrorContent)]
                                  (doseq [line stderr-lines]
                                    (log/stderr line))
                                  (put! out [nil StandardOutputContent]))))))))))))))))))