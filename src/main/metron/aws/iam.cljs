(ns metron.aws.iam
  (:require-macros [metron.macros :refer [with-promise edn-res-chan]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]))

(def IAM (js/require "@aws-sdk/client-iam"))
(def client (new (.-IAMClient IAM)))
(defn send [cmd] (edn-res-chan (.send client cmd)))

(defn get-user []
  (send (new (.-GetUserCommand IAM) #js{})))