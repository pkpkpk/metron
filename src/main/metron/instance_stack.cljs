(ns metron.instance-stack
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.aws.ssm :as ssm]
            [metron.bucket :as bkt]
            [metron.keypair :as kp]
            [metron.stack :as stack]
            [metron.logging :as log]
            [metron.util :refer [pipe1] :as util]))

(def describe-stack (partial stack/describe-stack "metron-instance-stack"))

(defn ^{:doc "cf will give stale info for ip/dns, unclear if iid ever changes"}
  get-stack-outputs []
  (with-promise out
    (take! (stack/get-stack-outputs "metron-instance-stack")
      (fn [[err {InstanceId :InstanceId :as outputs} :as res]]
        (if err
          (put! out res)
          (take! (ec2/describe-instance InstanceId)
            (fn [[err {:keys [PublicDnsName PublicIpAddress]} :as res]]
              (if err
                (put! out res)
                (put! out [nil (assoc outputs
                                      :PublicDnsName PublicDnsName
                                      :PublicIpAddress PublicIpAddress)])))))))))

(defn instance-id []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil InstanceId]))))))

(defn describe []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (pipe1 (ec2/describe-instance InstanceId) out))))))

(defn instance-state []
  (with-promise out
    (take! (describe)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get-in ok [:State :Name])]))))))

(defn wait-for-ok
  ([]
   (with-promise out
     (take! (get-stack-outputs)
       (fn [[err {:keys [InstanceId] :as ok} :as res]]
         (if err
           (put! out res)
           (pipe1 (wait-for-ok InstanceId) out))))))
  ([iid]
   (with-promise out
     (take! (instance-state)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (if (= "running" ok)
              (pipe1 (get-stack-outputs) out)
              (take! (do
                       (log/info "starting instance " iid)
                       (ec2/start-instance iid))
                (fn [_]
                  (log/info "Waiting for instance ok" iid)
                  (take! (ec2/wait-for-ok iid)
                    (fn [[err ok :as res]]
                      (if err
                        (put! out res)
                        (pipe1 (get-stack-outputs) out)))))))))))))

(defn ssh-args []
  (with-promise out
    (take! (wait-for-ok)
      (fn [[err {:keys [PublicDnsName InstanceId]} :as res]]
        (if err
          (put! out res)
          (take! (kp/ensure-authorized InstanceId)
            (fn [[err key-path :as res]]
              (if err
                (put! out res)
                (put! out [nil (str key-path " ec2-user@" PublicDnsName)])))))))))

(defn wait-for-stopped
  ([]
   (with-promise out
     (take! (get-stack-outputs)
       (fn [[err {:keys [InstanceId] :as ok} :as res]]
         (if err
           (put! out res)
           (pipe1 (wait-for-stopped InstanceId) out))))))
  ([iid]
   (with-promise out
     (take! (instance-state)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (if (= "stopped" ok)
              (put! out [nil])
              (take! (do
                       (log/info "stopping instance " iid)
                       (ec2/stop-instance iid))
                (fn [_]
                  (log/info "Waiting for instance to stop" iid)
                  (take! (ec2/wait-for-stopped iid)
                    (fn [[err ok :as res]]
                      (if err
                        (put! out res)
                        (put! out [nil])))))))))))))

(defn run-script [cmd]
  (with-promise out
    (take! (instance-id)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (pipe1 (ssm/run-script ok cmd) out))))))

(defn get-cloud-init-log []
  (run-script "cat /var/log/cloud-init-output.log"))

(defn stack-params [key-pair-name]
  {:StackName "metron-instance-stack"
   :DisableRollback true ;;TODO
   :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
   :TemplateBody (io/slurp (util/asset-path "templates" "instance_stack.json"))
   :Parameters [#js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}]})

(defn bin-files []
  (let [parent (io/file (util/asset-path "scripts" "bin"))
        bin-scripts (map #(.getPath %) (.listFiles parent))]
    (conj bin-scripts (util/dist-path "metron_webhook_handler.js"))))

(defn upload-files-to-bucket [{:keys [Bucket region] :as opts}]
  (with-promise out
    (take! (bkt/upload-file (util/asset-path "scripts" "install.sh"))
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (bkt/upload-files (bin-files) "bin")
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (let [config (pr-str {:region region :Bucket Bucket})]
                  (pipe1 (bkt/put-object "bin/config.edn" config) out))))))))))

(defn install [{:keys [Bucket region InstanceId] :as opts}]
  (assert (some? Bucket))
  (assert (some? region))
  (with-promise out
    (log/info "Uploading files to " Bucket)
    (take! (upload-files-to-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (let [cmd (str "sudo -u ec2-user aws s3 cp s3://" Bucket "/install.sh install.sh")]
                   (log/info "Downloading files from " Bucket " to " InstanceId)
                   (ssm/run-script InstanceId cmd))
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (let [cmd (str "chmod +x ./install.sh && ./install.sh " Bucket " " region)]
                  (log/info "Installing handlers on instance " InstanceId)
                  ;;TODO if err download install.log
                  (pipe1 (ssm/run-script InstanceId cmd) out))))))))))

(defn docker-service-running? [iid]
  (with-promise out
    (take! (ssm/run-script iid "sudo systemctl is-active docker")
      (fn [[err {:keys [StandardOutputContent] :as ok} :as res]]
        (if err
          (put! out res)
          (put! out [nil (= "active\n" StandardOutputContent)]))))))

(defn start-docker [iid] (ssm/run-script iid "sudo service docker start"))

(defn reboot-with-docker-service [iid]
  (with-promise out
    (let [user-data (io/slurp (util/asset-path "scripts" "post_install_userdata.sh"))]
      (log/info "rebooting instance, this may take a few minutes")
      (take! (ec2/restart-with-userdata iid user-data)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (take! (do
                     (log/info "instance ok, testing docker service")
                     (docker-service-running? iid))
              (fn [[err running? :as res]]
                (if err
                  (put! out res)
                  (if running?
                    (do (log/info "docker service running") (put! out [nil]))
                    (put! out [{:msg "error starting docker service using user-data"
                                :info ok}])))))))))))

(defn create-instance-stack [{:as opts}]
  (with-promise out
    (take! (bkt/ensure-bucket opts)
      (fn [[err Bucket :as res]]
        (if err
          (put! out res)
          (take! (kp/ensure-registered)
            (fn [[err {KeyName :KeyName} :as res]]
              (if err
                (put! out res)
                (take! (stack/create (stack-params KeyName))
                  (fn [[err {InstanceId :InstanceId :as outputs} :as res]]
                    (if err
                      (put! out res)
                      (take! (install (assoc opts :Bucket Bucket
                                                  :InstanceId InstanceId))
                        (fn [[err ok :as res]]
                          (if err
                            (put! out res)
                            (take! (reboot-with-docker-service InstanceId)
                              (fn [[err ok :as res]]
                                (if err
                                  (put! out res)
                                  (put! out [nil outputs]))))))))))))))))))

(defn delete-instance-stack []
  (with-promise out
    (take! (stack/delete "metron-instance-stack")
      (fn [[err :as res]]
        (if err
          (put! out res)
          (put! out [nil]))))))

(defn ensure-ok
  "if instance-stack does not exist, create it.
   if instance exists, wake it & return when ready.
   if docker is not running, start it (and warn)
   Yields instance-stack outputs"
  [opts]
  (with-promise out
    (take! (wait-for-ok)
      (fn [[err {iid :InstanceId :as outputs} :as res]]
        (if err
          (if (= "Stack with id metron-instance-stack does not exist" (.-message err))
            (pipe1 (create-instance-stack opts) out)
            (put! out res))
          (take! (docker-service-running? iid)
            (fn [[err running? :as res]]
              (if running?
                (put! out [nil outputs])
                (do
                  (log/warn "docker service not running! webhook stacks will not work. check user data")
                  (take! (start-docker iid)
                    (fn [[err ok :as res]]
                      (if err
                        (put! out res)
                        (put! out [nil outputs])))))))))))))

