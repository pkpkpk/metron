(ns metron.aws.ec2
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.logging :as log]
            [metron.util :refer [pipe1 p->res]]))

(def EC2 (js/require "@aws-sdk/client-ec2"))
(def client (new (.-EC2Client EC2)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(def ^:dynamic *poll-delay* 15)
(def ^:dynamic *max-wait-time* 500)

(defn describe-key-pairs
  "Describes the specified key pairs or all of your key pairs."
  ([] (send (new (.-DescribeKeyPairsCommand EC2) #js{})))
  ([& key-names]
   (send (new (.-DescribeKeyPairsCommand EC2) #js{:KeyNames (into-array key-names)
                                                  :IncludePublicKey true}))))

(defn describe-key-pair
  [key-name]
  (with-promise out
    (take! (send (new (.-DescribeKeyPairsCommand EC2) #js{:KeyNames #js[key-name]
                                                          :IncludePublicKey true}))
      (fn [[err {[k] :KeyPairs} :as res]]
        (if err
          (put! out res)
          (put! out [nil k]))))))

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

(defn import-key-pair
  [key-pair-name buf]
  (send (new (.-ImportKeyPairCommand EC2) #js{:KeyName key-pair-name
                                              :PublicKeyMaterial buf})))

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

(defn get-user-data [iid]
  (with-promise out
    (take! (send (new (.-DescribeInstanceAttributeCommand EC2)
                      #js{:InstanceId iid :Attribute "userData"}))
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [base64 (get-in ok [:UserData :Value])
                utf8 (.toString (.from js/Buffer base64 "base64") "utf8")]
            (put! out [nil utf8])))))))

(defn set-user-data "just give it a normal string"
  [iid user-data]
  (send (new (.-ModifyInstanceAttributeCommand EC2)
             #js{:InstanceId iid
                 :UserData #js{"Value" (js/Buffer.from user-data)}})))

(defn start-instance [iid]
  (send (new (.-StartInstancesCommand EC2) #js{:InstanceIds #js[iid]})))

(defn stop-instance [iid]
  (send (new (.-StopInstancesCommand EC2) #js{:InstanceIds #js[iid]})))

(defn wait-for-running [iid]
  (p->res (.waitUntilInstanceRunning EC2
                                     #js{:client client
                                         :maxDelay *poll-delay*
                                         :maxWaitTime *max-wait-time*}
                                     #js{:InstanceIds #js[iid]})))

(defn wait-for-ok [iid]
  (p->res (.waitUntilInstanceStatusOk EC2
                                      #js{:client client
                                          :maxDelay *poll-delay*
                                          :maxWaitTime *max-wait-time*}
                                      #js{:InstanceIds #js[iid]})))

(defn wait-for-stopped [iid]
  (p->res (.waitUntilInstanceStopped EC2
                                     #js{:client client
                                         :maxDelay *poll-delay*
                                         :maxWaitTime *max-wait-time*}
                                     #js{:InstanceIds #js[iid]})))

#!==============================================================================

(defn restart-with-userdata [iid user-data]
  (with-promise out
    (log/info "stopping instance " iid)
    (take! (stop-instance iid)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (take! (do
                   (log/info "Waiting for instance to stop " iid)
                   (wait-for-stopped iid))
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (take! (do
                        (log/info "overwriting user-data for instance " iid)
                        (set-user-data iid user-data))
                  (fn [[err ok :as res]]
                    (if err
                      (put! out res)
                      (take! (do
                               (log/info "restarting instance " iid)
                               (start-instance iid))
                        (fn [[err ok :as res]]
                          (if err
                            (put! out res)
                            (pipe1 (wait-for-ok iid) out)))))))))))))))
