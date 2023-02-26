(ns metron.server.main
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs.nodejs :as nodejs]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as string]
            [metron.aws :refer [AWS]]
            [metron.aws.ec2 :as ec2]
            [metron.util :as util :refer [*debug* pp]]))

(nodejs/enable-util-print!)

(def foo 42)

(defn -main [event-json]
  (let []))

(set! *main-cli-fn* -main)