(ns metron.stack
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!
                                     close! >! <! pipe timeout]]
            [clojure.string :as string]
            [metron.aws.cloudformation :as cf]
            [metron.logging :as log]
            [metron.util :refer [pipe1] :as util]))

(def ^:dynamic *poll-interval* 9000)

(defn observe-stack-events
  "this will get events that have already happened... consumers need to discriminate"
  [stack-arn filter-fn end-states]
  (with-promise out
    (let [_seen (atom #{})
          ident (juxt :ResourceType :ResourceStatus)
          _exit-event (atom false)]
      (go-loop [[err ok :as res] (<! (cf/describe-stack-events stack-arn))]
        (if err
          (put! out res)
          (let [stack (sort-by :Timestamp (filter (or filter-fn any?) (get ok :StackEvents)))]
            ;; https://docs.aws.amazon.com/AWSJavaScriptSDK/v3/latest/clients/client-cloudformation/interfaces/describestackeventscommandoutput.html#nexttoken
            (doseq [{resource :ResourceType,
                     state :ResourceStatus, :as event} stack]
              (when (and (= resource "AWS::CloudFormation::Stack")(end-states state))
                (reset! _exit-event event))
              (when-not (@_seen (ident event))
                (do
                  (swap! _seen conj (ident event))
                  (log/info (string/join " " (ident event))))))
            (if @_exit-event
              (put! out [nil @_exit-event])
              (do
                (<! (timeout *poll-interval*))
                (recur (<! (cf/describe-stack-events stack-arn)))))))))))

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
    (take! (cf/describe-stack-events stack-arn)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [stack (get ok :StackEvents)
                target (filterv #(and (= (:ResourceStatus %) "CREATE_FAILED")
                                      (not (string/includes? (:ResourceStatusReason %) "cancelled"))) stack)]
            (put! out [nil target])))))))

(defn describe-stack [stack-name]
  "assumes single stack"
  (with-promise out
    (take! (cf/describe-stack stack-name)
      (fn [[err ok :as res]]
        (if (some? err)
          (put! out res)
          (put! out [nil (get-in ok [:Stacks 0])]))))))

(defn get-stack-outputs [stack-name]
  (with-promise out
    (take! (describe-stack stack-name)
      (fn [[err {:keys [Outputs] :as ok} :as res]]
        (if err
          (put! out res)
          (let [m (into {}
                       (map (fn [{:keys [OutputKey OutputValue]}]
                              [(keyword OutputKey) OutputValue]))
                       Outputs)]
            (put! out [nil m])))))))

(defn create [{:keys [StackName] :as stack-params}]
  (with-promise out
    (take! (cf/create-stack stack-params)
      (fn [[err {sid :StackId :as ok} :as res]]
        (if err
          (put! out res)
          (do
            (log/info "Creating " StackName " " sid)
            (take! (observe-stack-creation sid)
              (fn [[err last-event :as res]]
                (if err
                  (put! out res)
                  (if (= ["AWS::CloudFormation::Stack" "CREATE_COMPLETE"]
                         ((juxt :ResourceType :ResourceStatus) last-event))
                    (pipe1 (get-stack-outputs StackName) out)
                    (take! (get-creation-failure sid)
                      (fn [[err ok :as res]]
                        (if err
                          (put! out [{:msg "Failed retrieving creation failure"
                                      :cause err}])
                          (let [msg (get-in ok [0 :ResourceStatusReason])]
                            (put! out [{:msg (str "Failed creating " StackName ": " msg)
                                        :cause ok}])))))))))))))))

(defn delete [StackName]
  (with-promise out
    (take! (describe-stack StackName)
      (fn [[err {sid :StackId} :as res]]
        (if err
          (if (string/ends-with? (.-message err) "does not exist")
            (put! out [nil])
            (put! out res))
          (take! (cf/delete-stack sid)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (do
                  (log/info "Deleting " StackName " " sid)
                  (take! (observe-stack-deletion sid)
                    (fn [[err last-event :as res]]
                      (if err
                        (put! out res)
                        (if (not= ["AWS::CloudFormation::Stack" "DELETE_COMPLETE"]
                                  ((juxt :ResourceType :ResourceStatus) last-event))
                          (put! out [{:msg (str "Failed deleting " StackName)
                                      :last-event last-event}])
                          (put! out res))))))))))))))