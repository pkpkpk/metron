(ns metron.aws.ssm
  (:require-macros [metron.macros :refer [edn-res-chan with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take! go-loop <! timeout]]
            [metron.aws :refer [AWS]]
            [metron.util :refer [pipe1]]))

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

(defn describe-instance-info []
  (edn-res-chan (.describeInstanceInformation SSM #js{})))