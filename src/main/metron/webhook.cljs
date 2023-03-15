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
            [metron.bucket :refer [ensure-bucket] :as bkt]
            [metron.keypair :as kp]
            [metron.aws.lambda :as lam]
            [metron.util :refer [*debug* dbg pipe1] :as util]))

(def path (js/require "path"))

(def ^:dynamic *asset-path* "assets")

(defn describe-stack []
  "assumes single stack"
  (with-promise out
    (take! (cf/describe-stack "metron-webhook-stack")
      (fn [[err ok :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil (get-in ok [:Stacks 0])]))))))

(defn get-stack-outputs []
  (with-promise out
    (take! (describe-stack)
      (fn [[err {:keys [Outputs] :as ok} :as res]]
        (if err
          (put! out res)
          (let [m (into {}
                        (map (fn [{:keys [OutputKey OutputValue]}]
                               [(keyword OutputKey) OutputValue]))
                        Outputs)]
            (put! out [nil m])))))))

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

(defn instance-state []
  (with-promise out
    (take! (instance-status)
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

(defn stop-instance []
  (with-promise out
    (take! (get-stack-outputs)
      (fn [[err {:keys [InstanceId] :as ok} :as res]]
        (if (some? err)
          (put! out res)
          (if (some? InstanceId)
            (pipe1 (ec2/stop-instance InstanceId) out)
            (put! out [nil])))))))

(defn stack-params [key-pair-name secret]
  (let [keypair #js{"ParameterKey" "KeyName"
                    "ParameterValue" key-pair-name}
        wh-secret #js{"ParameterKey" "WebhookSecret"
                      "ParameterValue" secret}
        wh-src #js{"ParameterKey" "WebhookSrc"
                   "ParameterValue" (io/slurp (.join path *asset-path* "js" "lambda.js"))}]
    {:StackName "metron-webhook-stack"
     :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
     :TemplateBody (io/slurp (.join path *asset-path* "templates" "webhook.json"))
     :Parameters [keypair wh-secret wh-src]}))

(defn create-stack [{:keys [key-pair-name WebhookSecret] :as arg}]
  (with-promise out
    (take! (cf/create-stack (stack-params key-pair-name WebhookSecret))
      (fn [[err {sid :StackId :as ok} :as res]]
        (if err
          (put! out res)
          (do
            (println "Creating metron-webhook-stack " sid)
            (take! (cf/observe-stack-creation sid)
              (fn [[err last-event :as res]]
                (if err
                  (put! out res)
                  (if (= ["AWS::CloudFormation::Stack" "CREATE_COMPLETE"]
                         ((juxt :ResourceType :ResourceStatus) last-event))
                    (pipe1 (get-stack-outputs) out)
                    (take! (cf/get-creation-failure sid)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out [{:msg "Failed retrieving creation failure"
                                      :cause err}])
                          (let [msg (get-in ok [0 :ResourceStatusReason])]
                            (put! out [{:msg (str "Failed creating webhook stack: " msg)
                                        :cause ok}])))))))))))))))

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
                  (println "Deleting metron-webhook-stack " sid)
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

(defn generate-deploy-key [iid]
  (with-promise out
    (let [script (io/slurp "assets/scripts/keygen.sh")]
      (println "Generating deploy key on instance...")
      (take! (ssm/run-script iid script)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (put! out [nil (get ok :StandardOutputContent)])))))))

(defn verify-deploy-key [iid]
  (with-promise out
    (take! (ssm/run-script iid "sudo -u ec2-user ssh -i .ssh/id_rsa -T git@github.com")
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
      (println "verifying deploy key...")
      (let [[err ok] (<! (verify-deploy-key iid))]
        (if (nil? err)
          (do
            (println "Deploy-key successfully configured")
            (put! out [nil]))
          (do
            (println "deploy-key configuration failed, please try again")
            (recur)))))))

(defn configure-deploy-key [{iid :InstanceId, :as opts}]
  (assert (some? iid))
  (println "retrieving deploy-key from stack instance...")
  (with-promise out
    (take! (wait-for-instance iid)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (generate-deploy-key iid)
            (fn [[err deploy-key :as res]]
              (if err
                (put! out res)
                (pipe1 (prompt-deploy-key-to-user iid deploy-key) out)))))))))

(defn webhook-secret-prompt [{:keys [WebhookUrl WebhookSecret] :as opts}]
  (println "")
  (println "1) go to https://github.com/:user/:repo/settings/hooks")
  (println "2) click 'Add webhook'")
  ; (println "3) In the 'Payload URL' field enter '" (str (subs WebhookUrl 0 (dec (alength WebhookUrl))) "?branch=metron") "'")
  (println "3) In the 'Payload URL' field enter '" WebhookUrl "'")
  (println "4) In the 'Content type' select menu choose 'application/json'")
  (println "5) In the 'Secret' field enter (without quotes): '" WebhookSecret "'")
  (println "6) Choose 'Just the push event' and click Add webhook to finish")
  (println ""))

(defn prompt-webhook-secret-to-user [{ :as opts}]
  (with-promise out
    (go-loop []
      (webhook-secret-prompt opts)
      (<! (util/get-acknowledgment))
      (println "waiting for ping response -> s3://metronbucket/pong.edn ...")
      ;; TODO look for push events to skip work
      (let [[err ok :as res] (<! (bkt/wait-for-pong))]
        (if (nil? err)
          (do
            (<! (bkt/delete-pong))
            (println "Webhook ping successfully processed!")
            (put! out res))
          (do
            (println "Failed to process ping: " (.-message err))
            (println " - make sure there is no whitespace in secret")
            (println " - remember to set payload content-type to application/json")
            (recur)))))))

(defn push-event-prompt [{:as opts}]
  (println "")
  (println "On the metron branch of the configured repo trigger a push event:")
  (println "  git commit --allow-empty -m \"Empty-Commit\"")
  (println ""))

(defn verify-webhook-push [] (bkt/wait-for-result))

(defn confirm-push-event [pong-event {:as opts}]
  (with-promise out
    (go-loop []
      (<! (bkt/delete-result))
      (push-event-prompt opts)
      (<! (util/get-acknowledgment))
      (println "waiting for s3://metronbucket/result.edn ...")
      (let [[err ok :as res] (<! (verify-webhook-push))]
        (if (nil? err)
          (do
            (println "Webhook push successfully processed!")
            (pipe1 (bkt/get-result) out))
          (do
            (println "Failed to process push: " (.-message err))
            (recur)))))))

(defn configure-webhook
  ([]
   (with-promise out
     (take! (get-stack-outputs)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (pipe1 (configure-webhook ok) out))))))
  ([{:keys [WebhookSecret WebhookUrl] :as opts}]
   (assert (string? WebhookSecret))
   (assert (not (string/blank? WebhookSecret)))
   (assert (string? WebhookUrl))
   (assert (not (string/blank? WebhookUrl)))
   (with-promise out
    (take! (configure-deploy-key opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (bkt/delete-pong)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (prompt-webhook-secret-to-user opts)
                  (fn [[err pong-event :as res]]
                    (if err
                      (put! out res)
                      (pipe1 (confirm-push-event pong-event opts) out)))))))))))))

(defn setup-bucket [opts]
  (with-promise out
    (take! (ensure-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (io/aslurp "dist/metron_server.js")
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (pipe1 (bkt/put-object "metron_server.js" ok) out)))))))))

(defn run-script [cmd]
  (with-promise out
    (take! (instance-id)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (pipe1 (ssm/run-script ok cmd) out))))))

(defn setup-server []
  (with-promise out
    (take! (run-script "aws s3 cp s3://metronbucket/metron_server.js metron_server.js")
      (fn [[err :as res]]
        (if err
          (put! out res) ;;work around for npm bullshit during userdata
          (pipe1 (run-script ["npm install aws-sdk";;to dodge region bs
                              "npm install @aws-sdk/client-s3"]) out))))))

(defn shutdown []
  (with-promise out
    (take! (lam/set-env-entry "metron_webhook_lambda" "SHOULD_SHUTDOWN_INSTANCE" "true")
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (stop-instance)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (put! out [nil "Webhook creation complete"])))))))))

(defn create-webhook-stack
  [{:keys [key-pair-name] :as opts}]
  (with-promise out
    (take! (setup-bucket opts)
      (fn [[err ok :as res]]
        (if err
          (put! out [{:msg "metron.webhook/setup-bucket failed"
                      :cause err}])
          (take! (do (println "metronbucket OK")
                     (kp/validate-keypair key-pair-name))
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (let [_(println "key-pair OK")
                      opts (assoc opts :WebhookSecret (util/random-string))]
                  (take! (create-stack opts)
                    (fn [[err outputs :as res]]
                      (if err
                        (put! out res)
                        (take! (setup-server)
                          (fn [[err :as res]]
                            (if err
                              (put! out res)
                              (take! (configure-webhook outputs)
                                (fn [[err ok :as res]]
                                  (if err
                                    (put! out res)
                                    (pipe1 (shutdown) out)))))))))))))))))))

(defn delete-webhook [arg] (delete-stack arg))