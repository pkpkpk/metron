(ns metron.webhook-stack
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.lambda :as lam]
            [metron.aws.ssm :as ssm]
            [metron.bucket :as bkt]
            [metron.instance-stack :as instance]
            [metron.stack :as stack]
            [metron.logging :as log]
            [metron.util :refer [pipe1] :as util]))

(def describe-stack (partial stack/describe-stack "metron-webhook-stack"))

(def get-stack-outputs (partial stack/get-stack-outputs "metron-webhook-stack"))

(defn generate-deploy-key [iid]
  (with-promise out
    (take! (ssm/run-script iid "./bin/keygen.sh")
      (fn [[err {stdout :StandardOutputContent} :as res]]
        (if err
          (put! out res)
          (let [key (-> stdout
                      (string/replace ".ssh/id_rsa already exists.\nOverwrite (y/n)? "  "")
                      string/trim)]
            (put! out [nil key])))))))

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

(def readline (js/require "readline"))

(defn get-acknowledgment []
  (with-promise out
    (let [rl (.createInterface readline #js{:input (.-stdin js/process) :output (.-stdout js/process)})]
      (.question rl "hit any key to continue"
        (fn [answer]
          (.close rl)
          (close! out))))))

(defn prompt-deploy-key-to-user [iid deploy-key]
  (with-promise out
    (go-loop []
      (deploy-key-prompt deploy-key)
      (<! (get-acknowledgment))
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
  (log/info "retrieving deploy-key from stack instance...")
  (with-promise out
    (take! (instance/wait-for-ok iid)
      (fn [[err _ :as res]]
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

(defn wait-for-pong []
  (with-promise out
    (log/info "waiting for ping response")
    (take! (bkt/wait-for-object "pong.edn")
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (do
                   (log/info "ping response found")
                   (bkt/delete-object "pong.edn"))
                (fn [[err]]
                  (when err (log/warn err))
                  (put! out [nil]))))))))

(defn prompt-webhook-secret-to-user [{ :as opts}]
  (with-promise out
    (go-loop []
      (webhook-secret-prompt opts)
      (<! (get-acknowledgment))
      (println "waiting for ping result ...")
      (let [[err ok :as res] (<! (wait-for-pong))]
        (if (nil? err)
          (do
            (println "Webhook ping successfully processed!")
            (put! out res))
          (do
            (println "Failed to process ping: " (.-message err))
            (println "In github check recent deliveries under hooks:")
            (println " - ResponseCode 401: be sure to set payload content-type to application/json")
            (println " - ResponseCode 402: make sure there is no whitespace in secret")
            (println " - ResponseCode 403: the url is wrong or the lambda has been deleted")
            (println " - ResponseCode 204: you sent something other than a ping event")
            (recur)))))))

;;TODO do this automatically
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
      (<! (get-acknowledgment))
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
    (log/info "Starting webhook configuration...")
    (take! (configure-deploy-key opts)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (bkt/delete-object "pong.edn")
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
          (take! (instance/wait-for-stopped)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (put! out [nil "Webhook creation complete. Instance is stopped"])))))))))

(defn sim-ping
  ([]
   (with-promise out
     (take! (instance/instance-id)
       (fn [[err ok :as res]]
         (if err
           (put! out res)
           (pipe1 (sim-ping ok) out))))))
  ([iid]
    (let [cmd ["mkdir events"
               "echo \"{\\\"headers\\\":{\\\"x-github-event\\\":\\\"ping\\\"}}\" > events/test_ping.json"
               "./bin/metron-webhook.sh events/test_ping.json"]]
      (ssm/run-script iid cmd))))

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
     (log/info "Testing ping handling..")
     (take! (sim-ping iid)
      (fn [[err {:keys [ResponseCode StandardOutputContent] :as ok} :as res]]
        (if err
          (put! out res)
          (if (and (zero? ResponseCode)
                   (string/starts-with? StandardOutputContent "[nil {:msg \"pong.edn has successfully"))
            (pipe1 (wait-for-pong) out)
            (put! out [{:msg "unexpected ping test result"
                        :cause ok}]))))))))

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

(defn- check-instance
  [{:keys [key-pair-name] :as opts}]
  (with-promise out
    (log/info "checking instance state before creating webhook")
    (take! (bkt/ensure-bucket opts)
      (fn [[err bucket-name :as res]]
        (if err
          (put! out res)
            (take! (instance/ensure-ok opts)
              (fn [[err {InstanceId :InstanceId :as outputs} :as res]]
                (if err
                  (put! out res)
                  (take! (test-ping InstanceId)
                    (fn [[err ok :as res]]
                      (if err
                        (put! out res)
                        (put! out [nil outputs]))))))))))))

(defn- create-new-stack [InstanceId]
  (with-promise out
    (let [WebhookSecret (.toString (.randomBytes (js/require "crypto") 30) "hex")]
      (take! (stack/create (stack-params InstanceId WebhookSecret))
        (fn [[err outputs :as res]]
          (if err
            (put! out res)
            (take! (configure-webhook outputs)
              (fn [[err ok :as res]]
                (if err
                  (put! out res)
                  (pipe1 (shutdown) out))))))))))

(defn stack-exists? []
  (with-promise out
    (take! (describe-stack)
      (fn [[err ok :as res]]
        (if err
          (if (string/ends-with? (.-message err) "does not exist")
            (put! out [nil false])
            (put! out res))
          (put! out [nil true]))))))

(defn create-webhook-stack [opts]
  (with-promise out
    (take! (stack-exists?)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (if (true? ok)
            (pipe1 (configure-webhook) out)
            (take! (check-instance opts)
              (fn [[err {InstanceId :InstanceId} :as res]]
                (if err ;; TODO under what conditions can we re-install to proceed?
                  (put! out res)
                  (do
                    (log/info "Existing instance is ok, creating webhook stack")
                    (pipe1 (create-new-stack InstanceId) out)))))))))))

(defn delete-webhook-stack []
  (with-promise out
    (take! (stack/delete "metron-webhook-stack")
      (fn [[err :as res]]
        (if err
          (put! out res)
          (put! out [nil]))))))