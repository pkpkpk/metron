(ns metron.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [clojure.string :as string]
            [clojure.tools.cli :refer [parse-opts]]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.webhook :as wh]
            [metron.util :as util :refer [*debug*]]))

(nodejs/enable-util-print!)

(defn exit [status msg]
  (println msg)
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
   ; ["-k" "--keypath KEYPATH" "path to key" :default nil :parse-fn identity :validate [string?]]
   [nil "--create-webhook" "create webhook stack"]
   [nil "--delete-webhook" "delete webhook stack"]])

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

      ;; custom validation on arguments
      ; (and (= 1 (count arguments)) (= "status" (first arguments)))
      ; {:action ::status :options options}

      true
      {:action ::help
       :opts options
       :exit-message (usage summary) :ok? true})))



(defn -main [& args]
  (let [{:keys [action options exit-message ok?] :as arg} (validate-args args)]
    (println "\n===========================================================================")
    (js/console.log (pr-str arg))
    (println "===========================================================================\n")
    (if (some? exit-message)
      (exit (if ok? 0 1) exit-message)
      (if (nil? action)
        (exit 0 nil)
        (go
         (let [[err ok] (<! (case action
                              ::create-webhook (wh/create-webhook arg)
                              ::delete-webhook (println arg)
                              (to-chan! [[{:msg (str "umatched action: " (pr-str action))}]])))]
           (if err
             (exit 1 err)
             (exit 0 ok))))))))

(set! *main-cli-fn* -main)
