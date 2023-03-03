(ns metron.aws.lambda
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [metron.aws :refer [AWS]]
            [metron.util :refer [pipe1]]))

(def Lambda (new (.-Lambda AWS) #js{:apiVersion "2016-11-15"}))

(defn get-config [fn-name]
  (edn-res-chan (.getFunctionConfiguration Lambda #js{:FunctionName fn-name})))

(defn get-env [fn-name]
  (with-promise out
    (take! (get-config fn-name)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (put! out [nil (get-in ok [:Environment :Variables])]))))))

(defn set-env [fn-name env]
  (edn-res-chan (.updateFunctionConfiguration Lambda #js{:FunctionName fn-name
                                                         :Environment #js{:Variables (clj->js env)}})))

(defn set-env-entry [fn-name key value]
  (with-promise out
    (take! (get-env fn-name)
      (fn [[err ok :as res]]
        (if err
          (put! out res)
          (let [env (assoc ok key value)]
            (pipe1 (set-env fn-name env) out)))))))

