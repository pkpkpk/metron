(ns metron.remote
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop chan promise-chan put! take! close! >! <! to-chan!]]
            [cljs-node-io.core :as io :refer [spit slurp file]]
            [cljs-node-io.proc :as proc]
            [clojure.string :as string]
            [metron.git :as g]
            [metron.instance-stack :as instance]
            [metron.bucket :as bkt]
            [metron.logging :as log]
            [metron.util :as util :refer [pp pipe1]]))

(defn push [opts]
  (with-promise out
    (put! out [nil])))