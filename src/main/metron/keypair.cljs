(ns metron.keypair
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.logging :as log]
            [metron.util :refer [pipe1 pp]]))

(def path (js/require "path"))
(def os (js/require "os"))
(def crypto (js/require "crypto"))

(defn fingerprint-pem [pem]
  (let [pem (.trim pem)
        rsa (.createPrivateKey crypto pem)
        der (.export rsa #js{:format "der" :type "pkcs8"})
        sha1 (-> (.createHash crypto "sha1")
               (.update der)
               (.digest "hex"))]
    (string/join ":" (re-seq #".{1,2}" sha1))))

(defn ?existing-file
  ([]
   (?existing-file "metron"))
  ([key-name]
   (let [base (str key-name ".pem")
         file (io/file (.join path (.homedir os) ".ssh") base)]
     (if (.exists file)
       file
       (let [file (io/file (.homedir os) base)]
         (when (.exists file)
           file))))))

(defn- write-key [key-name key-material] ;=> [?err key-name]
  (with-promise out
    (let [base (str key-name ".pem")
          file (io/file (.join path (.homedir os) ".ssh") base)]
      (take! (io/aspit file key-material :mode 0600)
        (fn [[dotssh-err :as res]]
          (if (nil? dotssh-err)
            (do
              (log/info "Success writing key to " (.getPath file))
              (put! out [nil key-name]))
            (let [file (io/file (.homedir os) base)]
              (log/warn "failed writing key to " (.getPath file) ", trying " (.getPath file))
              (take! (io/aspit file key-material :mode 0600)
                (fn [[home-err ok :as res]]
                  (if home-err
                    (do
                      (log/err "Failed writing key to " (.getPath file))
                      (put! out [{:msg "failed writing ssh key"
                                  :cause [dotssh-err home-err]}]))
                    (do
                      (log/info "Success writing key to " (.getPath file))
                      (put! out [nil key-name]))))))))))))

(defn create
  ([key-name]
    (create key-name nil))
  ([key-name target-file]
   (with-promise out
     (take! (ec2/create-key-pair key-name)
       (fn [[err {KeyMaterial :KeyMaterial} :as res]]
         (if err
           (if (string/ends-with? (.-message err) "keypair already exists")
             (take! (do
                      (log/info "overwriting previously registered key with name" key-name)
                      (ec2/delete-key-pair key-name))
               (fn [[err ok :as res]]
                 (if err
                   (put! out res)
                   (pipe1 (create key-name target-file) out))))
             (put! out res))
           (do
             (log/info "creating new" key-name "key")
             (if (nil? target-file)
               (pipe1 (write-key key-name KeyMaterial) out)
               (take! (io/aspit target-file KeyMaterial :mode 0600)
                 (fn [[err ok :as res]]
                   (if err
                     (put! out res)
                     (put! out [key-name]))))))))))))

(defn delete [key-name]
  (with-promise out
    (log/info "deleting key" key-name)
    (take! (ec2/delete-key-pair key-name)
      (fn [[err :as res]]
        (if err
          (put! out res)
          (do
            (some-> (?existing-file key-name) (io/delete-file true))
            (put! out [nil])))))))

(defn ensure-existing-ok [pem-file key-name]
  "tests if local pem matches registered key"
  (with-promise out
    (take! (ec2/describe-key-pair key-name)
      (fn [[err {fp :KeyFingerprint} :as res]]
        (if err
          (if (string/ends-with? (.-message err) "does not exist")
            (do
              (log/warn "found existing metron.pem but not registered. overwriting")
              (pipe1 (create key-name pem-file) out))
            (put! out res))
          (if (= fp (fingerprint-pem (io/slurp pem-file)))
            (put! out [nil key-name])
            (do
              (log/warn "found existing metron.pem but fingerprint does not match. overwriting")
              (take! (ec2/delete-key-pair key-name)
                (fn [[err ok :as res]]
                  (if err
                    (put! out res)
                    (pipe1 (create key-name pem-file) out)))))))))))

(defn ensure-registered []
  (with-promise out
    (log/info "checking metron.pem")
    (if-let [file (?existing-file "metron")]
      (pipe1 (ensure-existing-ok file "metron") out)
      (pipe1 (create "metron") out))))

; TODO if a key is deleted want to associate new one with existing instance
; (defn ensure-associated [key-name iid])

