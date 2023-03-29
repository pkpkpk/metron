(ns metron.webhook-stack
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.aws.lambda :as lam]
            [metron.aws.ssm :as ssm]
            [metron.bucket :as bkt]
            [metron.instance-stack :as instance]
            [metron.keypair :as kp]
            [metron.stack :as stack]
            [metron.logging :as log]
            [metron.util :refer [pipe1] :as util]))

(def describe-stack (partial stack/describe-stack "metron-webhook-stack"))

(def get-stack-outputs (partial stack/get-stack-outputs "metron-webhook-stack"))

(defn generate-deploy-key [iid]
  (with-promise out
    (let [script (io/slurp "assets/scripts/keygen.sh")]
      (take! (ssm/run-script iid script)
        (fn [[err ok :as res]]
          (if err
            (put! out res)
            (let [stdout (get ok :StandardOutputContent)
                  key (-> stdout
                        (string/replace ".ssh/id_rsa already exists.\nOverwrite (y/n)? "  "")
                        string/trim)]
              (put! out [nil key]))))))))

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
    (take! (instance/wait-for-instance iid)
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

(defn delete-pong [] (bkt/delete-object "pong.edn"))
(defn wait-for-pong [] (bkt/wait-for-object "pong.edn"))

(defn prompt-webhook-secret-to-user [{ :as opts}]
  (with-promise out
    (go-loop []
      (webhook-secret-prompt opts)
      (<! (util/get-acknowledgment))
      (println "waiting for ping result ...")
      (let [[err ok :as res] (<! (wait-for-pong))]
        (if (nil? err)
          (do
            (<! (delete-pong))
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
  (println "    git checkout -b metron")
  (println "    git commit --allow-empty -m \"Empty-Commit\"")
  (println "    git push <gh-remote> metron")
  (println ""))

(defn confirm-push-event [pong-event {:as opts}]
  (with-promise out
    (go-loop []
      (<! (bkt/delete-result))
      (push-event-prompt opts)
      (<! (util/get-acknowledgment))
      (println "waiting for result ...")
      (let [[err ok :as res] (<! (bkt/wait-for-result))]
        (if (nil? err)
          (do
            (println "Webhook push successfully processed!")
            (pipe1 (bkt/read-result) out))
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
          (take! (delete-pong)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (prompt-webhook-secret-to-user opts)
                  (fn [[err pong-event :as res]]
                    (if err
                      (put! out res)
                      (pipe1 (confirm-push-event pong-event opts) out)))))))))))))

(defn shutdown []
  (with-promise out
    (take! (lam/set-env-entry "metron_webhook_lambda" "SHOULD_SHUTDOWN_INSTANCE" "true")
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (instance/stop-instance)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (put! out [nil "Webhook creation complete. Instance is shutting down."])))))))))

(defn sim-ping
  ([]
   (with-promise out
     (take! (instance/instance-id)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (pipe1 (sim-ping ok) out))))))
  ([iid]
    (with-promise out
      (let [cmd "./bin/metron-webhook \"{\\\"headers\\\":{\\\"x-github-event\\\":\\\"ping\\\"}}\""]
        (log/info "Testing ping handling..")
        (take! (ssm/run-script iid cmd)
          (fn [[err {:keys [ResponseCode StandardOutputContent] :as ok} :as res]]
            (if err
              (put! out res)
              (if (and (zero? ResponseCode)
                       (string/starts-with? StandardOutputContent "[nil {:msg \"pong.edn has successfully"))
                (put! out [nil])
                (put! out [{:msg "unexpected ping test result"
                            :cause ok}])))))))))

(defn test-ping
  ([]
   (with-promise out
     (take! (instance/instance-id)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (pipe1 (test-ping ok) out))))))
  ([iid]
   (with-promise out
     (take! (sim-ping iid)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (do
                   (log/info "waiting for ping response")
                   (wait-for-pong))
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (do
                         (log/info "ping response found")
                         (delete-pong))
                  (fn [[err]]
                    (when err (log/warn err))
                    (put! out [nil]))))))))))))

;; test endpoint

(defn stack-params [InstanceId WebhookSecret]
  (let [wh-secret #js{"ParameterKey" "WebhookSecret"
                      "ParameterValue" WebhookSecret}
        instance-id #js{"ParameterKey" "InstanceId"
                        "ParameterValue" InstanceId}
        wh-src #js{"ParameterKey" "WebhookSrc"
                   "ParameterValue" (io/slurp (util/asset-path "js" "lambda.js"))}]
    {:StackName "metron-webhook-stack"
     :Capabilities #js["CAPABILITY_IAM" "CAPABILITY_NAMED_IAM"]
     :DisableRollback false
     :TemplateBody (io/slurp (util/asset-path "templates" "webhook_stack.json"))
     :Parameters [instance-id wh-secret wh-src]}))

;; check if already exists first
;; test ping before creationg
;; test endpoint before offering configuration
(defn create-webhook-stack
  [{:keys [key-pair-name] :as opts}]
  (with-promise out
    (take! (bkt/ensure-bucket opts)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
            (take! (instance/ensure-ok opts)
              (fn [[err {InstanceId :InstanceId} :as res]]
                (if err
                  (put! out res)
                  (let [WebhookSecret (util/random-string)]
                    (take! (stack/create (stack-params InstanceId WebhookSecret))
                      (fn [[err outputs :as res]]
                        (if err
                          (put! out res)
                          (take! (configure-webhook outputs)
                            (fn [[err ok :as res]]
                              (if err
                                (put! out res)
                                (pipe1 (shutdown) out))))))))))))))))

(defn delete-webhook-stack [](stack/delete "metron-webhook-stack"))