(ns metron.docker
  (:require-macros [metron.macros :refer [with-promise]])
  (:require [cljs.core.async :refer [go go-loop promise-chan put! take!]]
            [clojure.string :as string]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [metron.util :refer [*debug* dbg pipe1] :as util]))