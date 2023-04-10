(ns metron.remote
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs-node-io.core :as io :refer [spit slurp file]]
            [cljs-node-io.proc :as proc]
            [clojure.string :as string]
            [metron.aws.ssm :as ssm]
            [metron.git :as g]
            [metron.instance-stack :as instance]
            [metron.bucket :as bkt]
            [metron.logging :as log]
            [metron.util :as util :refer [pp pipe1 asset-path]]))

(def script (partial asset-path "scripts"))

(defn ensure-remote-repo [iid repo-name]
  (with-promise out
    (take! (ssm/run-script iid (slurp (script "create_repo.sh")) )
      (fn [[err ok :as res]]
        (put! out res)))))


(defn ?validate-cwd []
  (log/info "Validating" (.cwd js/process))
  (let [errs (atom [])]
    (when-not (.exists (io/file (.cwd js/process) "Dockerfile"))
      (swap! errs conj "missing Dockerfile"))
    (when-not (.exists (io/file (.cwd js/process) ".git"))
      (swap! errs conj "is not a git repo"))
    (not-empty @errs)))

(defn push [opts]
  (with-promise out
    (take! (instance/wait-for-ok)
      (fn [[err {:keys [InstanceId]} :as res]]
        (if err
          (put! out res)
          (if-let [err (?validate-cwd)]
            (put! out [err])
            (let [cwd (io/file (.cwd js/process))]
              (pipe1 (ensure-remote-repo InstanceId (.baseName cwd)) out))))))))