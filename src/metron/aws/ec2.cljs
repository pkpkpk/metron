(ns metron.aws.ec2
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.aws :refer [AWS]]))

(def EC2 (new (.-EC2 AWS) #js{:apiVersion "2016-11-15"}))

(defn describe-key-pairs
  "Describes the specified key pairs or all of your key pairs."
  ([]
   (edn-res-chan (.describeKeyPairs EC2)))
  ([& key-names]
   (edn-res-chan (.describeKeyPairs EC2 #js{:KeyNames (into-array key-names)
                                            :IncludePublicKey true}))))

(defn delete-key-pair
  ([key-name]
   (delete-key-pair key-name false))
  ([key-name dry-run?]
   (edn-res-chan (.deleteKeyPair EC2 #js{:DryRun dry-run? :KeyName key-name}))))

(defn create-key-pair
  ([key-pair-name]
   (create-key-pair key-pair-name false))
  ([key-pair-name dry-run?]
   (edn-res-chan (.createKeyPair EC2 #js{:KeyName key-pair-name
                                         :DryRun dry-run?}))))

(defn describe-instances []
  (edn-res-chan (.describeInstances EC2 #js{}))) ;#js{:InstanceIds #js[]}

(defn wait-for-running [iid]
  (edn-res-chan (.waitFor EC2 "instanceRunning" #js{:InstanceIds #js[iid]})))

(defn wait-for-ok [iid]
  (edn-res-chan (.waitFor EC2 "instanceStatusOk" #js{:InstanceIds #js[iid]})))
