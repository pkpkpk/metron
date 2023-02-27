(ns metron.keypair
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe to-chan!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.util :refer [*debug* dbg pipe1 pp]]))

; (def path (js/require "path"))

; (defn keypath
;   ([na](keypath ""))
;   ([base name]
;    (.join path base (str *key-pair-name* ".pem"))))

; (defn spit-key-file
;   ([data]
;    (spit-key-file "" data))
;   ([base data]
;    (let [dst (keypath base)]
;      (println "Writing ssh key to " dst)
;      (io/spit dst data :mode 600))))

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

