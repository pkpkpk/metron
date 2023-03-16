(ns metron.instance
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

(defn stack-params [key-pair-name]
  {:StackName "metron-instance-stack"
   ; :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
   :TemplateBody (io/slurp (util/asset-path "templates" "instance_stack.json"))
   :Parameters [#js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}]})

(defn create-stack [{:keys [key-pair-name] :as arg}]
  (stack/create (stack-params key-pair-name)))