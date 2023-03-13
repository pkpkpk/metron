(ns metron.aws.ec2
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.aws :refer [AWS]]))

; (def EC2 (new (.-EC2 AWS) #js{:apiVersion "2016-11-15"}))

(def EC2 (js/require "@aws-sdk/client-ec2"))
(def client (new (.-EC2Client EC2)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn describe-key-pairs
  "Describes the specified key pairs or all of your key pairs."
  ([] (send (new (.-DescribeKeyPairsCommand EC2))))
  ([& key-names]
   (send (new (.-DescribeKeyPairsCommand EC2) #js{:KeyNames (into-array key-names)
                                                  :IncludePublicKey true}))))

(defn delete-key-pair
  ([key-name]
   (delete-key-pair key-name false))
  ([key-name dry-run?]
   (send (new (.-DeleteKeyPairCommand EC2) #js{:DryRun dry-run?
                                               :KeyName key-name}))))

(defn create-key-pair
  ([key-pair-name]
   (create-key-pair key-pair-name false))
  ([key-pair-name dry-run?]
   (send (new (.-CreateKeyPairCommand EC2) #js{:DryRun dry-run?
                                               :KeyName key-pair-name}))))


(defn describe-instances
  ([]
   (send (new (.-DescribeInstancesCommand EC2) #js{})))
  ([instance-ids]
   (send (new (.-DescribeInstancesCommand EC2) #js{:InstanceIds (into-array instance-ids)}))))

(defn describe-instance [iid]
  (with-promise out
    (take! (send (new (.-DescribeInstancesCommand EC2) #js{:InstanceIds #js[iid]}))
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get-in ok [:Reservations 0 :Instances 0])]))))))

(defn start-instance [iid]
  (send (new (.-StartInstancesCommand EC2) #js{:InstanceIds #js[iid]})))

(defn stop-instance [iid]
  (send (new (.-StopInstancesCommand EC2) #js{:InstanceIds #js[iid]})))

(defn wait-for-running [iid]
  (edn-res-chan (.waitUntilInstanceRunning EC2 #js{:InstanceIds #js[iid]})))

(defn wait-for-ok [iid]
  (edn-res-chan (.waitUntilInstanceStatusOk EC2 #js{:InstanceIds #js[iid]})))

(defn wait-for-stopped [iid]
  (edn-res-chan (.waitUntilInstanceStopped EC2 #js{:InstanceIds #js[iid]})))
