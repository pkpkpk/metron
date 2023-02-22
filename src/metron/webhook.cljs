(ns metron.webhook
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.aws.cloudformation :as cf]
            [metron.aws.ssm :as ssm]
            [metron.keypair :as kp]
            [metron.bucket :refer [ensure-bucket]]
            [metron.util :refer [*debug* dbg] :as util]))

(def path (js/require "path"))

(def ^:dynamic *stack-name* "metron-stack")
(def ^:dynamic *secret* "b38737a2784c7d961af45397eecfed95e98e24c81c7ab1fadbb8ed09341e") ;TODO
(def ^:dynamic *asset-path* "assets")

;; TODO where to put?

(defn describe-stack []
  "assumes single stack"
  (with-promise out
    (take! (cf/describe-stacks *stack-name*)
      (fn [[err ok :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil (get-in ok [:Stacks 0])]))))))

(def stack-status describe-stack)

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

(defn create-stack [arg]
  (with-promise out
    (take! (kp/ensure-keypair arg)
      (fn [[err key-pair-name :as res]]
        (if err
          (put! out res)
          (take! (cf/create-stack (stack-params key-pair-name))
            (fn [[err {sid :StackId :as ok} :as res]]
              (if err
                (put! out res)
                (do
                  (println "Creating metron-stack " sid)
                  (take! (cf/observe-stack-creation sid)
                    (fn [[err last-event :as res]]
                      (if err
                        (put! out res)
                        (if (not= ["AWS::CloudFormation::Stack" "CREATE_COMPLETE"]
                                  ((juxt :ResourceType :ResourceStatus) last-event))
                          (put! out [{:msg "Failed creating webhook stack"
                                      ;;TODO retrieve actual cause
                                      :last-event last-event}])
                          (put! out res))))))))))))))

(defn delete-stack [_]
  (with-promise out
    (take! (describe-stack)
      (fn [[err {sid :StackId :as ok} :as res]]
        (if err
          (if (string/ends-with? (.-message err) "does not exist")
            (put! out [nil])
            (put! out res))
          (take! (cf/delete-stack sid)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (do
                  (println "Deleting metron-stack " sid)
                  (take! (cf/observe-stack-deletion sid)
                    (fn [[err last-event :as res]]
                      (if err
                        (put! out res)
                        (if (not= ["AWS::CloudFormation::Stack" "DELETE_COMPLETE"]
                                  ((juxt :ResourceType :ResourceStatus) last-event))
                          (put! out [{:msg "Failed deleting webhook stack"
                                      ;;TODO retrieve actual cause
                                      :last-event last-event}])
                          (put! out res))))))))))))))

(defn describe-instance []
  "assumes single instance"
  (with-promise out
    (take! (ec2/describe-instances)
      (fn [[err ok :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil (get-in ok [:Reservations 0 :Instances 0])]))))))

(defn instance-id []
  (with-promise out
    (take! (describe-instance)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get ok :InstanceId)]))))))

(defn generate-deploy-key []
  (with-promise out
    (take! (instance-id)
      (fn [[err iid :as res]]
        (if err
          (put! out res)
          (let [script (io/slurp "assets/scripts/keygen.sh")]
            (take! (ssm/run-script iid script)
              (fn [[err ok :as res]]
                (if err
                  (put! out res)
                  (put! out [nil (get ok :StandardOutputContent)]))))))))))

(defn verify-deploy-key []
  (with-promise out
    (take! (instance-id)
      (fn [[err instance :as res]]
        (if err
          (put! out res)
          (take! (ssm/run-script instance "sudo -u ec2-user ssh -T git@github.com")
            (fn [[err ok :as res]]
              (println "verify result:" (pr-str res))
              (put! out res))))))))

#!==============================================================================

(defn deploy-key-prompt [deploy-key]
  (println "")
  (println "1) go to https://github.com/<user>/<repo>/settings/keys")
  (println "2) click 'Add deploy key'")
  (println "3) enter everything between the lines into the text area")
  (println "\n===========================================================================")
  (println deploy-key)
  (println "===========================================================================\n"))

(defn configure-deploy-key [arg]
  (println "generating deploy key on stack instance...")
  (with-promise out
    (take! (generate-deploy-key)
      (fn [[err deploy-key :as res]]
        (if err
          (put! out res)
          (go-loop []
            (deploy-key-prompt deploy-key)
            (<! (util/get-acknowledgment))
            (let [[err ok] (<! (verify-deploy-key))]
              (if (nil? err)
                (do
                  (println "deploy key succssfully configured")
                  (put! out [nil]))
                (do
                  (println "deploy-key configuration failed, please try again")
                  (recur))))))))))

(defn create-webhook [arg]
  (println "starting webhook creation")
  (with-promise out
    (take! (ensure-bucket arg)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (create-stack arg)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (configure-deploy-key arg)
                  (fn [[:as res]]
                    (put! out res)))))))))))

(defn delete-webhook [arg]
  (with-promise out
    (take! (delete-stack arg)
      (fn [[err ok :as res]]
        (put! out res)))))