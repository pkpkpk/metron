(ns metron.aws.ssm
  (:require-macros [metron.macros :refer [edn-res-chan with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take! go-loop <! timeout]]
            [metron.aws :refer [AWS]]))

(defn pipe1 [a b]
  (take! a (fn [v] (put! b v)))
  b)

(def ^:dynamic *poll-interval* 3000)
(def ^:dynamic *max-retries* 10)

(def SSM (new (.-SSM AWS) #js{:apiVersion "2014-11-06"}))


(defn send-script-cmd [instance cmd]
  (edn-res-chan (.sendCommand SSM #js{:DocumentName "AWS-RunShellScript"
                                      :InstanceIds #js[instance]
                                      :Parameters #js{:commands #js[cmd]
                                                      :workingDirectory #js["/home/ec2-user"]}})))

(defn get-command-invocation [iid cid]
  (edn-res-chan (.getCommandInvocation SSM #js{:InstanceId iid :CommandId cid})))

(defn wait-for-command [iid cid]
  (let [_retries (atom *max-retries*)]
    (go-loop [[err {status :Status :as ok} :as res] (<! (get-command-invocation iid cid))]
      (cond
        (some? err) res
        (= "Success" status) res
        (#{"Failed" "Cancelled"} status) [ok]

        true
        (if (zero? @_retries)
          [(assoc ok :msg "Max retries reached")]
          (do
            (<! (timeout *poll-interval*))
            (swap! _retries dec)
            (recur (<! (get-command-invocation iid cid)))))))))

(defn run-script [iid cmd]
  (with-promise out
    (take! (send-script-cmd iid cmd)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [cid (get-in ok [:Command :CommandId])]
            (pipe1 (wait-for-command iid cid) out)))))))

; var params = {
;   DocumentName: 'STRING_VALUE', /* required */
;   AlarmConfiguration: {
;     Alarms: [ /* required */
;       {
;         Name: 'STRING_VALUE' /* required */
;       },
;       /* more items */
;     ],
;     IgnorePollAlarmFailure: true || false
;   },
;   CloudWatchOutputConfig: {
;     CloudWatchLogGroupName: 'STRING_VALUE',
;     CloudWatchOutputEnabled: true || false
;   },
;   Comment: 'STRING_VALUE',
;   DocumentHash: 'STRING_VALUE',
;   DocumentHashType: Sha256 | Sha1,
;   DocumentVersion: 'STRING_VALUE',
;   InstanceIds: [
;     'STRING_VALUE',
;     /* more items */
;   ],
;   MaxConcurrency: 'STRING_VALUE',
;   MaxErrors: 'STRING_VALUE',
;   NotificationConfig: {
;     NotificationArn: 'STRING_VALUE',
;     NotificationEvents: [
;       All | InProgress | Success | TimedOut | Cancelled | Failed,
;       /* more items */
;     ],
;     NotificationType: Command | Invocation
;   },
;   OutputS3BucketName: 'STRING_VALUE',
;   OutputS3KeyPrefix: 'STRING_VALUE',
;   OutputS3Region: 'STRING_VALUE',
;   Parameters: {
;     '<ParameterName>': [
;       'STRING_VALUE',
;       /* more items */
;     ],
;     /* '<ParameterName>': ... */
;   },
;   ServiceRoleArn: 'STRING_VALUE',
;   Targets: [
;     {
;       Key: 'STRING_VALUE',
;       Values: [
;         'STRING_VALUE',
;         /* more items */
;       ]
;     },
;     /* more items */
;   ],
;   TimeoutSeconds: 'NUMBER_VALUE'
; };
