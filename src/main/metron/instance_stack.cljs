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

(defn instance-status []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (pipe1 (ec2/describe-instance InstanceId) out))))))

(defn ssh-address []
  (with-promise out
    (take! (instance-status)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if-not (= "running" (get-in ok [:State :Name]))
            (put! out [{:msg "Instance is not running!"}])
            (let [public-dns (get ok :PublicDnsName)]
              (put! out [nil (str "ec2-user@" public-dns)]))))))))


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