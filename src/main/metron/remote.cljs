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

(defn ensure-remote-repo [iid repo-name]
  (ssm/run-script iid (str "./bin/create_repo.sh " repo-name)))

; (defn set-remote-url [url]
;   (aexec (str "git remote remove metron; git remote add metron "url" || true")))

(defn metron-remote-url [PublicDnsName repo-path]
  (assert (.isAbsolute (io/file repo-path)) (str "remote repo-path " repo-path " is not absolute"))
  (str "ssh://ec2-user@" PublicDnsName ":" repo-path #_".git"))

(defn git-prefix [key-path]
  (str "GIT_SSH_COMMAND=\"ssh -i " key-path "\""))

(defn git-push [url]
  (let [current-branch ":$(git rev-parse --abbrev-ref HEAD)"
        cmd (str (git-prefix "~/.ssh/metron.pem") " git push " url " " current-branch)]
    ; (aexec "git push metron :$(git rev-parse --abbrev-ref HEAD)")
    (aexec cmd)))

(defn ensure-worktree [iid repo-name]
  (ssm/run-script iid (str "./bin/convert_bare.sh " repo-name)))

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
        (fn [[err {:keys [StandardOutputContent] :as ok} :as res]]
          (if err
            (put! out res)
            (let [instance-repo-path (string/trim-newline StandardOutputContent)
                  remote-url (metron-remote-url PublicDnsName instance-repo-path)]
              (put! out [nil instance-repo-path]))))))))

(defn push [opts]
  (with-promise out
    (take! (instance/wait-for-ok)
      (fn [[err {:keys [InstanceId PublicDnsName] :as outputs} :as res]]
        (if err
          (put! out res)
          (if-let [err (?validate-cwd)]
            (put! out [err])
            (pipe1 (sync-repos outputs) out)))))))