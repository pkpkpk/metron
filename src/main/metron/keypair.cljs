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

(defn ?existing-file [key-pair-name]
  (let [base (str key-pair-name ".pem")
        file (io/file (.join path (.homedir os) ".ssh") base)]
    (if (.exists file)
      file
      (let [file (io/file (.homedir os) base)]
        (when (.exists file)
          file)))))

(defn key-is-registered? [key-name]
  (with-promise out
    (take! (ec2/describe-key-pairs key-name)
      (fn [[err ok]]
        (if err
          (put! out (not (string/includes? (.-message err) "does not exist")))
          (put! out true))))))

(defn- write-key [key-pair-name key-material] ;=> [?err key-pair-name]
  (with-promise out
    (let [base (str key-pair-name ".pem")
          file (io/file (.join path (.homedir os) ".ssh") base)]
      (take! (io/aspit file key-material)
        (fn [[dotssh-err :as res]]
          (if (nil? dotssh-err)
            (do
              (log/info "Success writing key to " (.getPath file))
              (put! out [nil key-pair-name]))
            (let [file (io/file (.homedir os) base)]
              (log/warn "failed writing key to " (.getPath file) ", trying " (.getPath file))
              (take! (io/aspit file key-material)
                (fn [[home-err ok :as res]]
                  (if home-err
                    (do
                      (log/err "Failed writing key to " (.getPath file))
                      (put! out [{:msg "failed writing ssh key"
                                  :cause [dotssh-err home-err]}]))
                    (do
                      (log/info "Success writing key to " (.getPath file))
                      (put! out [nil key-pair-name]))))))))))))

(defn create [key-pair-name]
  (with-promise out
    (take! (ec2/create-key-pair key-pair-name)
      (fn [[err {KeyMaterial :KeyMaterial} :as res]]
        (if err
          (put! out res)
          (pipe1 (write-key key-pair-name KeyMaterial) out))))))

(defn delete [key-pair-name]
  (with-promise out
    (take! (ec2/delete-key-pair key-pair-name)
      (fn [[err :as res]]
        (if err
          (put! out res)
          (do
            (some-> (?existing-file key-pair-name) (io/delete-file true))
            (put! out [nil])))))))

;===============================================================================

(def ^:dynamic *key-pair-name* "metron")

(defn ensure
  "If name is provided will treat as user provided, check-exists & registered.

   Otherwise will try to create a default 'metron.pem' key if it doesnt already exist.
   If local file exists but is not registered will try to overwrite.
   If local file exists and name is registed, assumes ok"
  ([]
    (with-promise out
      (log/info "checking metron.pem")
      (if-let [file (?existing-file "metron")]
        (take! (key-is-registered? "metron")
          (fn [[err ok :as res]]
            (if err
              (put! out res)
              (if (true? ok)
                (put! out [nil "metron"])
                (do
                  (io/delete-file file true)
                  (pipe1 (create "metron") out))))))
        (pipe1 (create "metron") out))))
  ([key-pair-name]
   (if (nil? key-pair-name)
     (ensure)
     (with-promise out
       (log/info "checking user provided key " key-pair-name)
       (set! *key-pair-name* key-pair-name)
       (if (nil? (?existing-file key-pair-name))
         (put! out [{:msg (str "Could not find user specified key-file " key-pair-name ".pem in $home directory or $home/.ssh")}])
         (take! (key-is-registered? key-pair-name)
           (fn [[err ok :as res]]
             (if err
               (put! out res)
               (if (true? ok)
                 (put! out [nil key-pair-name])
                 (put! out [{:msg "found key-file but key is not registered with ec2"}]))))))))))





