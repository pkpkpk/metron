(ns metron.aws.lambda
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.util :refer [pipe1]]))

(def Lambda (js/require "@aws-sdk/client-lambda"))
(def client (new (.-LambdaClient Lambda)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn get-config [fn-name]
  (send (new (.-GetFunctionConfigurationCommand Lambda) #js{:FunctionName fn-name})))

(defn set-config [fn-name config]
  (send (new (.-UpdateFunctionConfigurationCommand Lambda)
             (clj->js (merge {:FunctionName fn-name} config)))))

(defn get-env [fn-name]
  (with-promise out
    (take! (get-config fn-name)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get-in ok [:Environment :Variables])]))))))

(defn set-env [fn-name env]
  (set-config fn-name {:Environment {:Variables env}}))

(defn set-env-entry
  [fn-name & args]
  (with-promise out
    (take! (get-env fn-name)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [env (apply assoc ok args)]
            (pipe1 (set-env fn-name env) out)))))))

