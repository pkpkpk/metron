(ns metron.cli.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go >! <! to-chan!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [metron.aws.ec2 :as ec2]
            [metron.keypair :as kp]
            [metron.webhook-stack :as wh]
            [metron.instance-stack :as instance]
            [metron.util :as util :refer [pp]]))

(nodejs/enable-util-print!)

(defn exit [status data]
  (let [msg (cond
              (string? data)
               data

              (instance? js/Error data)
              (str (.-name data) " : " (.-message data))

              (:msg data)
              (:msg data)

               true
               data)]
    (if (zero? status)
      (when (some? msg)
        (if (string? msg)
          (.write (.-stdout js/process) msg)
          (.write (.-stdout js/process) (pp msg))))
      (let [s (str "metron_error_" (js/Date.now))]
        (.write (.-stderr js/process) (str msg \newline))
        (cond
          (instance? js/Error data)
          (let [f (cljs-node-io.file/createTempFile s ".tmp")]
            (.write (.-stdout js/process) (str (.getPath f) \newline))
            (io/spit f (str (.-stack data) \newline)))
          (map? data)
          (let [f (cljs-node-io.file/createTempFile s ".edn")]
            (.write (.-stdout js/process) (str (.getPath f) \newline))
            (io/spit f (pp data)))
          true
          (let [f (cljs-node-io.file/createTempFile s ".edn")]
            (.write (.-stdout js/process) (str (.getPath f) \newline))
            (io/spit f (pp {:msg data}))))))
    (.exit js/process status)))

(defn usage [options-summary]
  (->> ["Usage: node metron.js [options]*"
        ""
        "Options:"
        options-summary]
    (string/join \newline)))

(def cli-options
  [["-h" "--help"]
   [nil "--create-webhook" "create webhook stack"]
   [nil "--delete-webhook" "delete webhook stack"]
   [nil "--configure-webhook" "add/edit webhook with existing stack"]

   [nil "--create-instance" "create instance stack"]
   [nil "--delete-instance" "delete instance stack"]
   [nil "--status" "get description of instance state"]
   [nil "--start" "start instance"]
   [nil "--stop" "ensure instance is stopped"]
   [nil "--ssh" "return ssh dst into instance. without elastic-ip configured, changes every start/stop cycle"]

   ["-k" "--key-pair-name KEYPAIRNAME" "name of a SSH key registered with ec2"]
   ; ["-v" "--verbose"]
   ; ["-b" "--bucket BUCKETNAME" "name of bucket to use. If one is not provided one is created"]
   ; ["-p" "--profile AWS_PROFILE" "Defers to environment. Once set will be stored and reused"]
   ; ["-r" "--region AWS_REGION" "Defers to environment. Once set will be stored and reused"]
   ])

(defn error-msg [errors]
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

      (:start options)
      {:action ::start
       :opts (dissoc options :start)}

      (:stop options)
      {:action ::stop
       :opts (dissoc options :stop)}

      (:configure-webhook options)
      {:action ::configure-webhook
       :opts (dissoc options :configure-webhook)}

      (:create-instance options)
      {:action ::create-instance
       :opts (dissoc options :create-instance)}

      (:delete-instance options)
      {:action ::delete-instance
       :opts (dissoc options :delete-instance)}

      (:ssh options)
      {:action ::ssh
       :opts (dissoc options :ssh)}

      true
      {:action ::help
       :opts options
       :exit-message (usage summary) :ok? true})))

(defn resolve-region [opts]
  (goog.object.get (.-env js/process) "AWS_REGION"))

(defn dispatch-action [action opts]
  (case action
    ::create-webhook (wh/create-webhook-stack opts)
    ::delete-webhook (wh/delete-webhook-stack)
    ::configure-webhook (wh/configure-webhook)
    ::create-instance (instance/create-instance-stack opts)
    ::delete-instance (instance/delete-instance-stack)
    ::status (instance/describe)
    ::start (instance/wait-for-ok)
    ::stop (instance/wait-for-stopped)
    ::ssh (instance/ssh-address)
    (to-chan! [[{:msg (str "umatched action: " (pr-str action))}]])))

(defn -main [& args]
  (let [{:keys [action opts exit-message ok?] :as arg} (validate-args args)
        region (resolve-region opts)]
    (cond
      (nil? region)
      (exit 1 "please run with AWS_REGION set")

      (some? exit-message)
      (exit (if ok? 0 1) exit-message)

      (nil? action)
      (exit 0 nil)

      true
      (go
        (let [opts (assoc opts :region region)
              [err ok :as res] (<! (dispatch-action action opts))]
          (if err
            (exit 1 err)
            (exit 0 ok)))))))

(set! *main-cli-fn* -main)
