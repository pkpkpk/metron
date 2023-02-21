(ns metron.keypair
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take!
                                     close! >! <! pipe to-chan!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.util :refer [*debug* dbg]]))

(def path (js/require "path"))

(def ^:dynamic *key-pair-name* "metron")

(defn keypath
  ([](keypath ""))
  ([base]
   (.join path base (str *key-pair-name* ".pem"))))

;; TODO where to put?
(defn spit-key-file
  ([data]
   (spit-key-file "" data))
  ([base data]
   (io/spit (keypath base) data)))

(defn key-is-registered? [key-name]
  (with-promise out
    (take! (ec2/describe-key-pairs key-name)
      (fn [[err ok]]
        (if err
          (put! out (not (string/includes? (.-message err) "does not exist")))
          (put! out true))))))

;;TODO
;; no key provided => make one
;; key registered but not local => retrieve
;; keypath provided but key doesn't exist => exit w/ err
;; key provided but not registered => offer to importn
;; (.exists (io/file (keypath)))
(defn ensure-keypair
  "ensure local metron.pem"
  [{:as arg}]
  (with-promise out
    (take! (key-is-registered? *key-pair-name*)
      (fn [yes?]
        (if yes?
          (put! out [nil *key-pair-name*])
          (take! (ec2/create-key-pair *key-pair-name*)
            (fn [[err ok :as res]]
              (if err
                (put! out res)
                (do
                  (spit-key-file (.-KeyMaterial ok))
                  (put! out [nil *key-pair-name*]))))))))))

