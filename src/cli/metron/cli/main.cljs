(ns metron.cli.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go >! <! to-chan! put! take!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.file :refer [createTempFile]]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [metron.instance-stack :as instance]
            [metron.logging :as log]
            [metron.remote :as remote]
            [metron.webhook-stack :as wh]
            [metron.util :refer [pp p->res]]))

(nodejs/enable-util-print!)

(.on js/process "uncaughtException"
     (fn [err origin]
       (log/fatal (.-stack err))
       (set! (.-exitCode js/process) 99)))

(defn ssm-response-err-msg [m]
  (and (map? m)
       (contains? m :DocumentName)
       (str "Error running script on instance:" \newline
            (get m :StandardErrorContent))))

(def ^:dynamic *json* false)

(defn structured? [o]
  (and (not (string? o))
       (or (map? o)
           (vector? o))))

(defn exit [status data]
  (do
    (if (zero? status)
      (when data
        (let [output (if (string? data)
                       data
                       (if *json*
                         (js/JSON.stringify (clj->js data))
                         (pp data)))]
          (log/stdout output)))
      (if (instance? js/Error data)
        (let [s (str "metron_error_" (js/Date.now))
              f (cljs-node-io.file/createTempFile s ".tmp")]
          (io/spit f (.-stack data))
          (log/err (str (.-message data)))
          (log/err (str "more info in " (.getPath f))))
        (let [filename (str "metron_error_" (js/Date.now))
              [file content] (cond
                               (not (structured? data))
                               [(createTempFile filename ".tmp") data]

                               (true? *json*)
                               [(createTempFile filename ".json") (js/JSON.stringify (clj->js data))]

                               true
                               [(createTempFile filename ".edn") (pp data)])]
          (when-let [msg (if (string? data)
                           data
                           (when-let [msg (or (and (map? data) (:msg data))
                                              (ssm-response-err-msg data))]
                             msg))]
            (log/err msg))
          (io/spit file content)
          (log/err (str "more info in " (.getPath file))))))
    (.exit js/process status)))

(def actions #{:create-webhook :delete-webhook :create-instance :delete-instance
               :push :status :start :stop :configure-webhook :ssh :delete
               :describe-instance})

(defn usage [options-summary] ;; TODO distinguish actions and their args
  (->> ["Usage: node metron.js [options]*"
        ""
        "Options:"
        options-summary
        \newline]
    (string/join \newline)))

(def cli-options
  [["-h" "--help"]
   ["-q" "--quiet" "elide info logging from output to stderr"]
   ["-j" "--json" "prefer json for structured output"]

   [nil "--create-instance" "create instance stack"]
   [nil "--instance-type InstanceType" "choose instance type for stack creation"]
   [nil "--cores cores" "cpu cores" :parse-fn js/parseInt]
   [nil "--threads threads" "threads per core" :parse-fn js/parseInt]

   [nil "--push" "send latest commit from cwd to instance and run it"]
   [nil "--create-webhook" "create webhook stack"]
   [nil "--delete-webhook" "delete webhook stack"]
   [nil "--configure-webhook" "add/edit webhook with existing stack"]

   [nil "--delete-instance" "delete both instance and webhook stack. bucket is ignored"]
   [nil "--describe-instance" "return full description of instance"]
   [nil "--delete" "delete both instance and webhook stack. bucket is ignored"]
   [nil "--status" "get description of instance state"]
   [nil "--start" "start instance"]
   [nil "--stop" "ensure instance is stopped"]
   [nil "--ssh" "starts instance and opens ssh session. args are passed to ssh"]])

(defn error-msg [errors]
  (str "The following errors occurred while parsing your command:\n\n"
       (string/join \newline errors)
       \newline))

(defn validate-args [args]
  (let [{:keys [options arguments errors summary] :as opts} (parse-opts args cli-options)
        [action :as actions] (reduce #(if (contains? options %2) (conj %1 %2) %1) [] actions)]
    (cond
      (:help options) ; help => exit OK with usage summary
      {:exit-message (usage summary)
       :ok? true}

      (some? errors)
      {:exit-message (error-msg errors)
       :ok? false}

      (< 1 (count actions))
      (let [msg [(str "Only one action can be specified at a time, found " (count actions))
                 actions]]
        {:exit-message (error-msg msg)
         :ok? false})

      (nil? action)
      {:action :help
       :opts options
       :exit-message (usage summary) :ok? true}

      true
      {:action action
       :opts (dissoc options action)})))

(defn delete-stacks []
  (with-promise out
    (take! (wh/delete-webhook-stack)
      (fn [[err :as res]]
        (if err
          (put! out res)
          (take! (instance/delete-instance-stack)
            (fn [[err :as res]]
              (if err
                (put! out res)
                (put! out [nil])))))))))



(defn dispatch-action [action opts]
  (case action
    :create-webhook (wh/create-webhook-stack opts)
    :delete-webhook (wh/delete-webhook-stack)
    :configure-webhook (wh/configure-webhook)
    :create-instance (instance/ensure-ok opts)
    :delete-instance (delete-stacks)
    :describe-instance (instance/describe)
    :delete (delete-stacks)
    :status (instance/status)
    :start (instance/wait-for-ok)
    :stop (instance/wait-for-stopped)
    :ssh (instance/ssh-args)
    :push (remote/push opts)
    ; :delete-all bucket too?
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
           (when (:quiet opts)
             (set! metron.logging/*quiet?* true))
           (when (:json opts)
             (set! *json* true))
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
