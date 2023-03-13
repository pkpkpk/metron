(ns metron.aws.ssm
  (:require-macros [metron.macros :refer [edn-res-chan with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take! go-loop <! timeout]]
            [metron.aws :refer [AWS]]
            [metron.util :refer [pipe1]]))

(def ^:dynamic *poll-interval* 3000)
(def ^:dynamic *max-retries* 10)

(def SSM (js/require "@aws-sdk/client-ssm"))
(def client (new (.-SSMClient SSM)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn describe-instance-info []
  (send (new (.-DescribeInstanceInformationCommand SSM) #js{})))

(defn send-script-cmd [instance cmd]
  (send (new (.-SendCommandCommand SSM) #js{:DocumentName "AWS-RunShellScript"
                                            :InstanceIds #js[instance]
                                            :Parameters #js{:commands (if (string? cmd)
                                                                        #js[cmd]
                                                                        (into-array cmd))
                                                            :workingDirectory #js["/home/ec2-user"]}})))

(defn get-command-invocation [iid cid]
  (send (new (.-GetCommandInvocationCommand SSM) #js{:InstanceId iid :CommandId cid})))

(defn wait-for-command [iid cid]
  ; https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/clients/client-ssm/globals.html#waituntilcommandexecuted
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