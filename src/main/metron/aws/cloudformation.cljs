(ns metron.aws.cloudformation
  (:require-macros [metron.macros :refer [edn-res-chan with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! chan close! take! go-loop <! timeout]]
            [clojure.string :as string]
            [metron.util :refer [dbg]]))

(def CF (js/require "@aws-sdk/client-cloudformation"))
(def client (new (.-CloudFormationClient CF)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn create-stack [params]
  (send (new (.-CreateStackCommand CF) (clj->js params))))

(defn update-stack [params]
  (send (new (.-UpdateStackCommand CF) (clj->js params))))

(defn delete-stack [stack-name]
  (send (new (.-DeleteStackCommand CF) #js{:StackName (str stack-name)})))

(defn describe-stacks []
  (send (new (.-DescribeStacksCommand CF) #js{})))

(defn describe-stack [stack-name]
  (send (new (.-DescribeStacksCommand CF) #js{:StackName (str stack-name)})))

(defn validate-template [template-body]
  (send (new (.-ValidateTemplateInput CF) #js{:TemplateBody (str template-body)})))

(defn describe-stack-events
  ([stack-name] (describe-stack-events stack-name nil))
  ([stack-name next-token]
   (send (new (.-DescribeStackEventsCommand CF) #js{:StackName (str stack-name)
                                                    :NextToken next-token}))))

(defn wait-for-create [stack-name]
  (edn-res-chan (.waitUntilStackCreateComplete CF #js{:StackName stack-name})))

(defn wait-for-update [stack-name]
  (edn-res-chan (.waitUntilStackUpdateComplete CF #js{:StackName stack-name})))

(defn wait-for-delete [stack-name]
  (edn-res-chan (.waitUntilStackDeleteComplete CF #js{:StackName stack-name})))

(defn wait-for-import [stack-name]
  (edn-res-chan (.waitUntilStackImportComplete CF #js{:StackName stack-name})))

(defn wait-for-rollback [stack-name]
  (edn-res-chan (.waitUntilStackRollbackComplete CF #js{:StackName stack-name})))

