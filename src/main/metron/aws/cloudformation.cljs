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

(def ^:dynamic *poll-interval* 9000)

(defn observe-stack-events
  "this will get events that have already happened... consumers need to discriminate"
  [stack-arn filter-fn end-states]
  (with-promise out
    (let [_seen (atom #{})
          ident (juxt :ResourceType :ResourceStatus)
          _exit-event (atom false)]
      (go-loop [[err ok :as res] (<! (describe-stack-events stack-arn))]
        (if err
          (put! out res)
          (let [stack (sort-by :Timestamp (filter (or filter-fn any?) (get ok :StackEvents)))]
            ;; TODO check for NextToken
            ;; https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/clients/client-cloudformation/interfaces/describestackeventscommandoutput.html#nexttoken
            (doseq [{resource :ResourceType,
                     state :ResourceStatus, :as event} stack]
              (when (and (= resource "AWS::CloudFormation::Stack")(end-states state))
                (reset! _exit-event event))
              (when-not (@_seen (ident event))
                (swap! _seen conj (ident event))
                (dbg (string/join " " (ident event)))))
            (if @_exit-event
              (put! out [nil @_exit-event])
              (do
                (<! (timeout *poll-interval*))
                (recur (<! (describe-stack-events stack-arn)))))))))))

(defn observe-stack-creation [stack-name]
  (observe-stack-events stack-name nil
                        #{"CREATE_COMPLETE" "CREATE_FAILED"
                          "ROLLBACK_COMPLETE" "ROLLBACK_FAILED"
                          "DELETE_FAILED" "DELETE_COMPLETE"}))

(defn observe-stack-deletion [stack-name]
  (observe-stack-events stack-name
                        #(string/starts-with? (:ResourceStatus %) "DELETE")
                        #{"DELETE_FAILED" "DELETE_COMPLETE"}))

(defn observe-stack-update [stack-name]
  (observe-stack-events stack-name
                        #(string/starts-with? (:ResourceStatus %) "UPDATE")
                        #{"UPDATE_ROLLBACK_FAILED"
                          "UPDATE_ROLLBACK_COMPLETE"
                          "UPDATE_FAILED"
                          "UPDATE_COMPLETE"}))

(defn get-creation-failure [stack-arn]
  (with-promise out
    (take! (describe-stack-events stack-arn)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [stack (get ok :StackEvents)
                target (filterv #(and (= (:ResourceStatus %) "CREATE_FAILED")
                                      (not (string/includes? (:ResourceStatusReason %) "cancelled"))) stack)]
            (put! out [nil target])))))))
