(ns metron.keypair
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [metron.aws.ec2 :as ec2]
            [metron.aws.ssm :as ssm]
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
              (put! out [nil]))
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
                      (put! out [nil]))))))))))))

(defn create
  ([key-name]
    (create key-name nil))
  ([key-name target-file]
   (with-promise out
     (take! (ec2/create-key-pair key-name)
       (fn [[err {KeyMaterial :KeyMaterial :as key-desc} :as res]]
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
             (take! (if (nil? target-file)
                      (write-key key-name KeyMaterial)
                      (io/aspit target-file KeyMaterial :mode 0600))
                 (fn [[err ok :as res]]
                   (if err
                     (put! out res)
                     (put! out [nil key-desc])))))))))))

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

(defn- check-existing-key [pem-file key-name]
  (with-promise out
    (take! (ec2/describe-key-pair key-name)
      (fn [[err {fp :KeyFingerprint :as key-desc} :as res]]
        (if err
          (if (string/ends-with? (.-message err) "does not exist")
            (put! out [{:msg "existing key is not registered"
                        :type :not-registered
                        :cause err}])
            (put! out [{:msg "unknown error"
                        :cause err}]))
          (if (= fp (fingerprint-pem (io/slurp pem-file)))
            (put! out [nil key-desc])
            (put! out [{:msg "fingerprints do not match"
                        :type :bad-fingerprint
                        :old-key key-desc}])))))))

(defn- handle-key-error [pem-file err]
  (with-promise out
    (case (:type err)
      :not-registered
      (do
        (log/warn "found existing metron.pem but not registered. overwriting")
        (pipe1 (create "metron" pem-file) out))

      :bad-fingerprint
      (do
        (log/warn "found existing metron.pem but fingerprint does not match. overwriting")
        (take! (ec2/delete-key-pair "metron")
          (fn [[err ok :as res]]
            (if err
              (put! out res)
              (pipe1 (create "metron" pem-file) out)))))
      (put! out [err]))))

(defn- overwrite-authorized-keys [iid]
  (with-promise out
    (take! (ec2/describe-key-pair "metron")
      (fn [[err {:keys [PublicKey]} :as res]]
        (if err
          (put! out res)
          (let [cmd (str "echo \"" PublicKey "\" > .ssh/authorized_keys")]
            (log/info "adding new key to instance")
            (pipe1 (ssm/run-script iid cmd) out)))))))

#!==============================================================================

(defn ensure-registered "make sure we have a key-pair for ssh" []
  (with-promise out
    (log/info "checking metron.pem")
    (if-let [file (?existing-file "metron")]
      (take! (check-existing-key file "metron")
             (fn [[err ok :as res]]
               (if (nil? err)
                 (put! out res)
                 (pipe1 (handle-key-error file err) out))))
      (pipe1 (create "metron") out))))

(defn ensure-authorized "make sure we have a key that works with instance" [iid]
  (with-promise out
    (log/info "checking that metron.pem matches instance")
    (if-let [file (?existing-file "metron")]
      (take! (check-existing-key file "metron")
        (fn [[err ok :as res]]
          (if (nil? err)
            (do
              (log/info "existing key ok!")
              (put! out [nil (.getPath file)]))
            (take! (handle-key-error file err)
              (fn [[err key-desc :as res]]
                (if err
                  (put! out res)
                  (take! (overwrite-authorized-keys iid)
                    (fn [[err :as res]]
                      (if err
                        (put! out res)
                        (put! out [nil (.getPath file)]))))))))))
      (take! (create "metron")
        (fn [[err :as res]]
          (if err
            (put! out res)
            (let [key-path (.getPath (?existing-file "metron"))]
              (take! (overwrite-authorized-keys iid)
                (fn [[err :as res]]
                  (if err
                    (put! out res)
                    (put! out [nil key-path])))))))))))

