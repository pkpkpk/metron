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

; (defn registered-keypairs []
;   (with-promise out
;     (ec2/describe-key-pairs key-pair-name)))

;; TODO where to put?
(defn spit-key-file
  ([data]
   (spit-key-file "" data))
  ([base data]
   (io/spit (.join path base "metron.pem") data)))

(defn ensure-keypair [{:as arg}]
  (to-chan! [[{:msg "some error"}]])
  ; (with-promise out
  ;   (take! (ec2/describe-key-pairs key-pair-name)
  ;     (fn [[err ok :as res]]
  ;       (if err
  ;         (if-not (string/includes? (.-message err) "does not exist")
  ;           (put! out res)
  ;           (take! (ec2/create-key-pair key-pair-name)
  ;             (fn [[err ok :as res]]
  ;               (if err
  ;                 (put! out res)
  ;                 (do
  ;                   (spit-key-file key-pair-name (.-KeyMaterial ok))
  ;                   (put! out res))))))
  ;         (do
  ;           ;TODO ensure local pem, if not delete and make new one
  ;           (assert (.exists (io/file (str key-pair-name ".pem"))))
  ;           (put! out res))))))
  )


;; no key provided
;;  -- look for key metron.pem
;;    -- if yes, ensure registered, carry on
;;  -- generate && offer to install?
;; key path provided but key doesn't exist=
;;  -- exit & try again
;; key provided but not registered
;;; -- offer to import