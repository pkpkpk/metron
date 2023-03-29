(ns metron.util
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <!]]
            [cljs-node-io.core :as io]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]))

(defn pp [x] (with-out-str (pprint x)))

(defn pipe1 [a b]
  (take! a (fn [v] (put! b v)))
  b)

(def readline (js/require "readline"))

(defn get-acknowledgment []
  (with-promise out
    (let [rl (.createInterface readline #js{:input (.-stdin js/process) :output (.-stdout js/process)})]
      (.question rl "hit any key to continue"
        (fn [answer]
          (.close rl)
          (close! out))))))


(def crypto (js/require "crypto"))

(defn random-string []
  (.toString (.randomBytes crypto 30) "hex"))

(defn ->clj [o]
  (js->clj o :keywordize-keys true))

#!==============================================================================
;; cli only

(def path (js/require "path"))

(def ^:dynamic *asset-path* "assets")

(defn asset-path [& paths]
  (apply (.-join path) (into [*asset-path*] paths)))

(def ^:dynamic *dist-path* "dist")

(defn dist-path [& paths]
  (apply (.-join path) (into [*dist-path*] paths)))