(ns metron.aws.cloudformation
  (:require-macros [metron.macros :refer [edn-res-chan with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! chan close! take! go-loop <! timeout]]
            [clojure.string :as string]
            [metron.aws :refer [AWS]]
            [metron.util :refer [dbg]]))

(def CF (new (.-CloudFormation AWS) #js{:apiVersion "2010-05-15"}))

(defn create-stack [params]
  (edn-res-chan (.createStack CF (clj->js params))))

(defn wait-for-create [stack-name]
  (edn-res-chan (.waitFor CF "stackCreateComplete" #js{:StackName stack-name})))

(defn update-stack [params]
  (edn-res-chan (.updateStack CF (clj->js params))))

(defn wait-for-update [stack-name]
  (edn-res-chan (.waitFor CF "stackUpdateComplete" #js{:StackName stack-name})))

(defn describe-stacks [stack-name]
  (edn-res-chan  (.describeStacks CF #js{:StackName (str stack-name)})))

(defn wait-for-delete [stack-name]
  (edn-res-chan (.waitFor CF "stackDeleteComplete" #js{:StackName stack-name})))

(defn validate-template [template-body]
  (edn-res-chan (.validateTemplate CF #js{:TemplateBody (str template-body)})))

(defn delete-stack [stack-name]
  (edn-res-chan (.deleteStack CF #js{:StackName (str stack-name)})))

(defn describe-stack-events
  ([stack-name] (describe-stack-events stack-name nil))
  ([stack-name next-token]
   (edn-res-chan (.describeStackEvents CF #js{:StackName (str stack-name)
                                              :NextToken next-token}))))

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
