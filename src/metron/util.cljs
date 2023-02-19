(ns metron.util
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <!]]
            [cljs-node-io.core :as io]
            [clojure.string :as string]))

(def readline (js/require "readline"))

(def ^:dynamic *debug* true)

(defn dbg [& args]
  (when *debug*
    (.apply (.-log js/console) js/console (into-array args))))

(defn get-acknowledgment []
  (with-promise out
    (let [rl (.createInterface readline #js{:input (.-stdin js/process) :output (.-stdout js/process)})]
      (.question rl "hit any key continue"
        (fn [answer]
          (.close rl)
          (close! out))))))

(defn yes-or-no [question]
  (let [rl (.createInterface readline #js{:input (.-stdin js/process) :output (.-stdout js/process)})]
    (go-loop [])))

