(ns metron.cli.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go >! <! to-chan! put! take!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [metron.aws.ec2 :as ec2]
            [metron.instance-stack :as instance]
            [metron.keypair :as kp]
            [metron.logging :as log]
            [metron.webhook-stack :as wh]
            [metron.util :as util :refer [pp p->res]]))

(nodejs/enable-util-print!)

(.on js/process "uncaughtException"
     (fn [err origin]
       (log/fatal (.-stack err))
       (set! (.-exitCode js/process) 99)))

(defn exit [status data] ;; TODO when err return file paths? right now nothing
  (do
    (if (zero? status)
      (when data
        (let [output (if (string? data)
                       data
                       (pp data))]
          (log/stdout output)))
      (if (instance? js/Error data)
        (let [s (str "metron_error_" (js/Date.now))
              f (cljs-node-io.file/createTempFile s ".tmp")]
          (io/spit f (.-stack data))
          (log/err (str (.-message data)))
          (log/err (str "more info in " (.getPath f))))
        (let [msg (if (string? data)
                    data
                    (if-let [msg (and (map? data) (:msg data))]
                      msg
                      (str "Unknown error type " (type data))))
              s (str "metron_error_" (js/Date.now))
              f (cljs-node-io.file/createTempFile s ".tmp")]
          (io/spit f (pp data))
          (log/err msg)
          (log/err (str "more info in " (.getPath f))))))
    (.exit js/process status)))

(defn usage [options-summary]
  (->> ["Usage: node metron.js [options]*"
        ""
        "Options:"
        options-summary
        \newline]
    (string/join \newline)))

(def cli-options
  [["-h" "--help"]
   ; ["-v" "--verbose"]

   ; ["-l" "--log"]
   ; [nil "--log-path"]
   [nil "--create-webhook" "create webhook stack"]
   [nil "--delete-webhook" "delete webhook stack"]
   [nil "--configure-webhook" "add/edit webhook with existing stack"]
   [nil "--create-instance" "create instance stack"]
   [nil "--delete-instance" "delete instance stack"]
   [nil "--status" "get description of instance state"]
   [nil "--start" "start instance"]
   [nil "--stop" "ensure instance is stopped"]
   [nil "--ssh" "starts instance and opens ssh session. args are passed to ssh"]])

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

(defn dispatch-action [action opts]
  (case action
    ::create-webhook (wh/create-webhook-stack opts)
    ::delete-webhook (wh/delete-webhook-stack)
    ::configure-webhook (wh/configure-webhook)
    ::create-instance (instance/ensure-ok opts)
    ::delete-instance (instance/delete-instance-stack)
    ::status (instance/describe)
    ::start (instance/wait-for-ok)
    ::stop (instance/wait-for-stopped)
    ::ssh (instance/ssh-args)
    (to-chan! [[{:msg (str "umatched action: " (pr-str action))}]])))

(defn resolve-config [_]
  (let [ini (js/require "@aws-sdk/shared-ini-file-loader")
        profile (goog.object.get (.-env js/process) "AWS_PROFILE" "default")]
    (with-promise out
      (take! (p->res (.loadSharedConfigFiles ini #js{:profile profile}))
        ;; possible bug if profile not keyword safe
        (fn [[err {{config (keyword profile)} :configFile} :as res]]
          (if err
            (put! out res)
            (put! out [nil (assoc config :profile profile)])))))))

(defn -main [& args]
  (let [{:keys [action opts exit-message ok?] :as arg} (validate-args args)]
    (if (some? exit-message)
      (exit (if ok? 0 1) exit-message)
      (if (nil? action)
        (exit 0 nil)
        (go
         (let [[err {:keys [region] :as cfg} :as res] (<! (resolve-config opts))]
           (cond
             (some? err)
             (exit 1 err)

             (nil? region)
             (exit 1 (str "please set the region field for profile " (:profile cfg) " in .aws/config"))

             true
             (let [opts (assoc opts :region region)
                   [err ok :as res] (<! (dispatch-action action opts))]
               (if err
                 (exit 1 err)
                 (exit 0 ok))))))))))

(set! *main-cli-fn* -main)
