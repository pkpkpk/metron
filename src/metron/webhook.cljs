(ns metron.webhook
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.aws.cloudformation :as cf]
            [metron.keypair :as kp]
            [metron.util :refer [*debug* dbg]]))

(def path (js/require "path"))

(def ^:dynamic *stack-name* "metron-stack")
(def ^:dynamic *secret* "b38737a2784c7d961af45397eecfed95e98e24c81c7ab1fadbb8ed09341e") ;TODO
(def ^:dynamic *asset-path* "assets")

;; TODO where to put?


(def stack-event-debugger
  (map (fn [[err ok :as res]]
         (when *debug*
           (if err
             (dbg err)
             (dbg ((juxt :ResourceType :ResourceStatus) ok))))
         res)))

(defn stack-params [key-pair-name]
  (let [keypair #js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}
        wh-secret #js{"ParameterKey" "WebhookSecret"
                      "ParameterValue" *secret*}
        wh-src #js{"ParameterKey" "WebhookSrc"
                   "ParameterValue" (io/slurp (.join path *asset-path* "js" "lambda.js"))}]
    {:StackName *stack-name*
     :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
     :TemplateBody (io/slurp (.join path *asset-path* "templates" "webhook.json"))
     :Parameters [keypair wh-secret wh-src]}))

(defn create-stack
  "returns [err] or stream of unique [nil event-map] until stack terminal stack
   event in chronological order"
  [arg]
  (with-promise out
    (take! (kp/ensure-keypair arg)
      (fn [[err key-pair-name :as res]]
        (if err
          (put! out res)
          (take! (cf/create-stack (stack-params key-pair-name))
            (fn [[err {sid :StackId :as ok} :as res]]
              (println "Creating metron-stack " sid)
              (if err
                (put! out res)
                (take! (cf/observe-stack-creation sid)
                  (fn [[err last-event :as res]]
                    (if err
                      (put! out res)
                      (if (not= ["AWS::CloudFormation::Stack" "CREATE_COMPLETE"]
                                ((juxt :ResourceType :ResourceStatus) last-event))
                        (put! out [{:msg "Failed creating webhook stack"
                                    ;;TODO retrieve actual cause
                                    :last-event last-event}])
                        (put! out res)))))))))))))

(defn create-webhook [arg]
  (with-promise out
    (take! (create-stack arg)
      (fn [[err ok :as res]]
        (put! out res)))))