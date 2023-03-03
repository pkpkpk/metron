(ns metron.docker
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.util :refer [*debug* dbg pipe1] :as util]))

(def path (js/require "path"))

(defn local-dir-path [{:keys [full_name]}]
  (.join path "metron_repos" full_name))

(defn process-event [{:keys [] :as event}]
  (with-promise out
    (put! out [nil {:msg ""}])))