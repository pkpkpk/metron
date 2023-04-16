(ns metron.build
  (:require [clojure.tools.build.api :as b]
            [cljs.build.api :as api]
            [clojure.java.io :as io]
            [cljs.util]))

(def version (format "0.0.%s" (b/git-count-revs nil)))

(def cli-config
  {:parallel-build true
   :cache-analysis true
   :main 'metron.cli.main
   :optimizations :simple
   :target :nodejs
   :output-to  "dist/metron_cli.js"})

(def webhook-config
  {:parallel-build true
   :cache-analysis true
   :main 'metron.webhook-handler
   :optimizations :simple
   :target :nodejs
   :output-to  "dist/metron_webhook_handler.js"})

(defn build-cli []
  (let [sources (api/inputs "src/lib" "src/shared" "src/main")
        cli-config (assoc cli-config :closure-defines {'metron.cli.main/VERSION version})]
    (api/build sources cli-config)))

(defn build-webhook []
  (let [sources (api/inputs "src/lib" "src/shared" "src/webhook")]
    (api/build sources webhook-config)))

(defn build [& args]
  (println "cleaning dist")
  (b/delete {:path "dist"})
  (println "building cli")
  (build-cli)
  (println "building webhook")
  (build-webhook)
  (b/delete {:path "out"})
  (println "finished building"))