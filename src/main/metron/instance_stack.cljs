(ns metron.instance-stack
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.aws.lambda :as lam]
            [metron.aws.ssm :as ssm]
            [metron.bucket :refer [ensure-bucket] :as bkt]
            [metron.keypair :as kp]
            [metron.stack :as stack]
            [metron.util :refer [*debug* dbg pipe1] :as util]))

(def describe-stack (partial stack/describe-stack "metron-instance-stack"))

(def get-stack-outputs (partial stack/get-stack-outputs "metron-instance-stack"))

(defn instance-id []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil InstanceId]))))))

(defn describe-instance []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (pipe1 (ec2/describe-instance InstanceId) out))))))

(defn ssh-address []
  (with-promise out
    (take! (describe-instance)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if-not (= "running" (get-in ok [:State :Name]))
            (put! out [{:msg "Instance is not running!"}])
            (let [public-dns (get ok :PublicDnsName)]
              (put! out [nil (str "ec2-user@" public-dns)]))))))))

(defn instance-state []
  (with-promise out
    (take! (describe-instance)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get-in ok [:State :Name])]))))))

(defn wait-for-instance
  ([]
   (with-promise out
     (take! (get-stack-outputs)
       (fn [[err {:keys [InstanceId] :as ok} :as res]]
         (if err
           (put! out res)
           (pipe1 (wait-for-instance InstanceId) out))))))
  ([iid]
   (with-promise out
     (take! (instance-state)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (if (= "running" ok)
              (put! out res)
              (take! (do (println "starting instance...") (ec2/start-instance iid))
                (fn [_]
                  (println "Waiting for instance ok" iid)
                  (pipe1 (ec2/wait-for-ok iid) out))))))))))

(def start-instance wait-for-instance)

(defn stop-instance []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (if (some? InstanceId)
            (pipe1 (ec2/stop-instance InstanceId) out)
            (put! out [nil])))))))

(defn stack-params [key-pair-name]
  {:StackName "metron-instance-stack"
   :DisableRollback true
   :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
   :TemplateBody (io/slurp (util/asset-path "templates" "instance_stack.json"))
   :Parameters [#js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}]})

(defn setup-bucket [opts]
  (with-promise out
    (take! (ensure-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (io/aslurp "dist/metron_remote_handler.js")
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (pipe1 (bkt/put-object "metron_remote_handler.js" ok) out)))))))))

(defn create-instance-stack
  [{:keys [key-pair-name] :as opts}]
  (with-promise out
    (take! (setup-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (kp/validate-keypair key-pair-name)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (stack/create (stack-params key-pair-name))
                  (fn [[err outputs :as res]]
                    (put! out res)))))))))))

(defn delete-instance-stack [] (stack/delete "metron-instance-stack"))

(defn ensure-ok
  "if instance-stack does not exist, create it.
   if instance exists, wake it & return when ready.
   Yields instance-stack outputs"
  [opts]
  (with-promise out
    (take! (wait-for-instance)
      (fn [[err ok :as res]]
        (if (nil? err)
          (pipe1 (get-stack-outputs) out)
          (if (= "Stack with id metron-instance-stack does not exist" (.-message err))
            (pipe1 (create-instance-stack opts) out)
            (put! out res)))))))

(defn run-script [cmd]
  (with-promise out
    (take! (instance-id)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (pipe1 (ssm/run-script ok cmd) out))))))