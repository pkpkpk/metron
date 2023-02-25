(ns metron.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.keypair :as kp]
            [metron.webhook :as wh]
            [metron.util :as util :refer [*debug* pp]]))

(nodejs/enable-util-print!)

(defn exit [status msg]
  (println msg) ;; TODO use stdout/stderr
  (.exit js/process status))

(defn usage [options-summary]
  (->> ["Usage: node metron.js [options]*"
        ""
        "Options:"
        options-summary]
    (string/join \newline)))

(def cli-options
  [["-h" "--help"]
   ; ["-v" "--verbose"]
   [nil "--status"]
   [nil "--create-webhook" "create webhook stack"]
   [nil "--delete-webhook" "delete webhook stack"]
   [nil "--sleep" "ensure instance is stopped"]
   ["-k" "--key-pair-name KEYPAIRNAME" "name of a SSH key registered with ec2"]
   ; [nil "--gen-key" "generate metron.pem"]
   ])

(defn error-msg [errors] ;;TODO tailor error messages to optis
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as opts} (parse-opts args cli-options)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary)
       :ok? true}

      (some? errors)
      {:exit-message (error-msg errors)
       :ok? false}

      (:create-webhook options)
      {:action ::create-webhook
       :opts (dissoc options :create-webhook)}

      (:delete-webhook options)
      {:action ::delete-webhook
       :opts (dissoc options :delete-webhook)}

      (:status options)
      {:action ::status
       :opts (dissoc options :status)}

      (:sleep options)
      {:action ::sleep
       :opts (dissoc options :sleep)}

      ;; custom validation on arguments
      ; (and (= 1 (count arguments)) (= "status" (first arguments)))
      ; {:action ::status :options options}

      true
      {:action ::help
       :opts options
       :exit-message (usage summary) :ok? true})))

(defn -main [& args]
  (let [{:keys [action opts exit-message ok?] :as arg} (validate-args args)]
    ; (println "\n===========================================================================")
    ; (js/console.log (pr-str arg))
    ; (println "===========================================================================\n")
    (cond
      (nil? (goog.object.get (.-env js/process) "AWS_REGION"))
      (exit 1 "please run with AWS_REGION set")

      (some? exit-message)
      (exit (if ok? 0 1) exit-message)

      (nil? action)
      (exit 0 nil)

      true
      (go
       (let [opts (assoc opts :region (goog.object.get (.-env js/process) "AWS_REGION"))
             _(println "region" (goog.object.get (.-env js/process) "AWS_REGION"))
             [err ok :as res] (<! (case action
                                    ::create-webhook (wh/create-webhook-stack opts)
                                    ; ::configure-webhook
                                    ::delete-webhook (wh/delete-webhook opts)
                                    ::status (wh/instance-status)
                                    ::sleep (wh/stop-instance)
                                    ;;stack-outputs
                                    ;;update-webhook-cmd
                                    ;;update-lambda-code
                                    (to-chan! [[{:msg (str "umatched action: " (pr-str action))}]])))]
         (if err
           (exit 1 (str "\nError:\n\n" (pp err)))
           (exit 0 (str "\nSuccess:\n\n" (pp ok)))))))))

(set! *main-cli-fn* -main)
