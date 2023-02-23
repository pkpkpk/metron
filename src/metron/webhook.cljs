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
            [metron.util :refer [*debug* dbg pipe1] :as util]))

(def path (js/require "path"))

(def ^:dynamic *asset-path* "assets")

(defn describe-stack []
  "assumes single stack"
  (with-promise out
    (take! (cf/describe-stacks "metron-stack")
      (fn [[err ok :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil (get-in ok [:Stacks 0])]))))))

(def stack-event-debugger
  (map (fn [[err ok :as res]]
         (when *debug*
           (if err
             (dbg err)
             (dbg ((juxt :ResourceType :ResourceStatus) ok))))
         res)))

(defn stack-params [key-pair-name secret]
  (let [keypair #js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}
        wh-secret #js{"ParameterKey" "WebhookSecret"
                      "ParameterValue" secret}
        wh-src #js{"ParameterKey" "WebhookSrc"
                   "ParameterValue" (io/slurp (.join path *asset-path* "js" "lambda.js"))}]
    {:StackName "metron-stack"
     :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
     :TemplateBody (io/slurp (.join path *asset-path* "templates" "webhook.json"))
     :Parameters [keypair wh-secret wh-src]}))

(defn create-stack [{:keys [key-pair-name secret] :as arg}]
  (with-promise out
    (take! (cf/create-stack (stack-params key-pair-name secret))
      (fn [[err {sid :StackId :as ok} :as res]]
        (if err
          (put! out res)
          (do
            (println "Creating metron-stack " sid)
            (take! (cf/observe-stack-creation sid)
              (fn [[err last-event :as res]]
                (if err
                  (put! out res)
                  (if (= ["AWS::CloudFormation::Stack" "CREATE_COMPLETE"]
                         ((juxt :ResourceType :ResourceStatus) last-event))
                    (put! out res)
                    (take! (cf/get-creation-failure sid)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out [{:msg "Failed retrieving creation failure"
                                      :cause err}])
                          (put! out [{:msg "Failed creating webhook stack"
                                      :cause ok}]))))))))))))))

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

(defn get-stack-outputs []
  (with-promise out
    (take! (describe-stack)
      (fn [[err {:keys [Outputs] :as ok} :as res]]
        ))))

(defn instance-id []
  (with-promise out
    (take! (describe-stack)
      (fn [[err {:keys [Outputs] :as ok} :as res]] ;; TODO outputs as map would be nice
        (if (some? err)
          (put! out res)
          (let [s (filter #(= "InstanceId" (:OutputKey %)) Outputs)
                iid (get (first s) :OutputValue)]
            (put! out [nil iid])))))))


#!==============================================================================

(defn generate-deploy-key [iid]
  (with-promise out
    (let [script (io/slurp "assets/scripts/keygen2.sh")]
      (take! (ssm/run-script iid script)
        (fn [[err ok :as res]]
          (println "keygen.sh res:" (util/pp res))
          (if err
            (put! out res)
            (put! out [nil (get ok :StandardOutputContent)])))))))

(defn verify-deploy-key [iid]
  (with-promise out
    (take! (ssm/run-script iid "sudo -u ec2-user ssh -i id_rsa -T git@github.com")
      (fn [[{:keys [StandardErrorContent] :as err} ok :as res]]
        (if (and err (string/includes? StandardErrorContent "successfully authenticated"))
          (put! out [nil])
          (put! out res))))))

(defn deploy-key-prompt [deploy-key]
  (println "")
  (println "1) go to https://github.com/:user/:repo/settings/keys")
  (println "2) click 'Add deploy key'")
  (println "3) enter everything between the lines into the text area")
  (println "\n===========================================================================")
  (println deploy-key)
  (print "===========================================================================\n"))

(defn prompt-deploy-key-to-user [iid deploy-key]
  (with-promise out
    (go-loop []
      (deploy-key-prompt deploy-key)
      (<! (util/get-acknowledgment))
      (let [[err ok] (<! (verify-deploy-key iid))]
        (if (nil? err)
          (do
            (println "deploy key succssfully configured")
            (put! out [nil]))
          (do
            (println "deploy-key configuration failed, please try again")
            (recur)))))))

(defn configure-deploy-key [arg]
  (println "generating deploy key on stack instance...")
  (with-promise out
    (take! (instance-id)
      (fn [[err iid :as res]]
        (if err
          (put! out res)
          (take! (do
                   (println "Waiting for instance " iid)
                   (ec2/wait-for-ok iid))
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (generate-deploy-key iid)
                  (fn [[err deploy-key :as res]]
                    (if err
                      (put! out res)
                      (pipe1 (prompt-deploy-key-to-user iid deploy-key) out))))))))))))

(defn create-webhook [{:keys [key-pair-name] :as opts}]
  (with-promise out
    (take! (ensure-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (kp/validate-keypair key-pair-name)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (let [secret (util/random-string)]
                  (println "Starting webhook creation")
                  (take! (create-stack (assoc opts :secret secret))
                    (fn [[err ok :as res]]
                      (if err
                        (put! out res)
                        (take! (configure-deploy-key opts)
                          (fn [[:as res]]
                            (put! out res)))))))))))))))


(defn delete-webhook [arg]
  (with-promise out
    (take! (delete-stack arg)
      (fn [[err ok :as res]]
        (put! out res)))))