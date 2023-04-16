(ns metron.util
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <!]]
            [cljs-node-io.core :as io]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]))

(defn pp [x] (with-out-str (pprint x)))

(defn ->clj [o] (js->clj o :keywordize-keys true))

(defn pipe1 [a b] (take! a (fn [v] (put! b v))) b)

(defn p->res [p]
  (with-promise out
    (.then p
           #(put! out (cond-> [nil] (some? %) (conj (->clj %))))
           #(put! out [(->clj %)]))))

#!==============================================================================
;; cli only

(def path (js/require "path"))

(def ^:dynamic *asset-path* (.join path (.getParent (io/file js/__dirname)) "assets"))

(defn asset-path [& paths]
  (apply (.-join path) (into [*asset-path*] paths)))

(def ^:dynamic *dist-path* js/__dirname)

(defn dist-path [& paths]
  (apply (.-join path) (into [*dist-path*] paths)))