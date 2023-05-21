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
            [metron.util :refer [asset-path pipe1]]))

(def path (js/require "path"))

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
                  key-file (kp/?existing-file)
                  _(assert (some? key-file) "missing key file!")
                  key-path (.getPath key-file)
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
    (log/info "building container" tag)
    (ssm/run-script iid cmd {:cwd non-bare-path})))

(defn docker-run [iid non-bare-path tag]
  (let [cmd (str "sudo -u ec2-user docker run " tag)]
    (log/info "running container...")
    (ssm/run-script iid cmd {:cwd non-bare-path})))

(defn non-bare-path [repo-name]
  (.join path "/home/ec2-user/remote_repos" repo-name))

(defn _run [opts InstanceId]
  ;; TODO warn when uncommited changes
  (with-promise out
    (let [cwd (io/file (.cwd js/process))
          repo-name (.getName cwd)
          non-bare-path (non-bare-path repo-name)
          tag (short-sha)]
      (take! (docker-build InstanceId non-bare-path tag)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (take! (docker-run InstanceId non-bare-path tag)
              (fn [[err ok :as res]]
                (if err
                  (let [{:keys [StandardOutputContent StandardErrorContent]} err]
                    (log/stderr StandardOutputContent)
                    (log/stderr \newline)
                    (put! out res))
                  (let [{:keys [StandardOutputContent StandardErrorContent]} ok]
                    (log/stderr StandardErrorContent)
                    (log/stderr \newline)
                    (put! out [nil StandardOutputContent])))))))))))

(defn push [opts]
  (with-promise out
    (if-let [errors (?validate-cwd)]
      (put! out [{:errors errors :msg "Invalid local repository"}])  ;;TODO this doesnt report well
      (take! (instance/wait-for-ok)
        (fn [[err {:keys [InstanceId PublicDnsName] :as outputs} :as res]]
          (if err
            (put! out res)
            (take! (do
                    (log/info "pushing" (.cwd js/process) "to remote" InstanceId)
                    (sync-repos outputs))
              (fn [[err ok :as res]]
                (if err
                  (put! out res)
                  (if (get opts :run)
                    (pipe1 (_run opts InstanceId) out)
                    (do
                      (log/info "successfully git pushed code to repo")
                      (let [{:keys [StandardOutputContent StandardErrorContent]} ok]
                        (log/stderr StandardErrorContent)
                        (log/stderr \newline)
                        (put! out [nil StandardOutputContent])))))))))))))

(defn run [opts]
  (with-promise out
    (if-let [errors (?validate-cwd)]
      (put! out [{:errors errors :msg "Invalid local repository"}]) ;;TODO this doesnt report well
      (take! (instance/wait-for-ok)
        (fn [[err {:keys [InstanceId] :as outputs} :as res]]
          (if err
            (put! out res)
            (pipe1 (_run opts InstanceId) out)))))))