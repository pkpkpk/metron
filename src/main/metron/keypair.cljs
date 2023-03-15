(ns metron.keypair
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.util :refer [*debug* dbg pipe1 pp]]))

; (defn create-new []
;   (with-promise out
;     (take! (ec2/create-key-pair *key-pair-name*)
;       (fn [[err ok :as res]]
;         (if err
;           (put! out res)
;           (do
;             (spit-key-file (:KeyMaterial ok))
;             (put! out [nil *key-pair-name*])))))))

;;TODO ensure-keypair
;; key registered but not found locally => create new one
;; key provided but not registered => offer to import
;; (.exists (io/file (keypath)))
; (io/spit dst data :mode 600)

(defn key-is-registered? [key-name]
  (with-promise out
    (take! (ec2/describe-key-pairs key-name)
      (fn [[err ok]]
        (if err
          (put! out (not (string/includes? (.-message err) "does not exist")))
          (put! out true))))))

(defn validate-keypair
  "ensure local metron.pem"
  [key-pair-name]
  (with-promise out
    (if (nil? key-pair-name)
      (put! out [{:msg "please provide the name of a registered key-pair with option '-k <keyname>'"}])
      (take! (key-is-registered? key-pair-name)
        (fn [yes?]
          (if yes?
            (put! out [nil])
            (put! out [{:msg "please provide the name of a registered key-pair with option '-k <keyname>'"}])))))))

