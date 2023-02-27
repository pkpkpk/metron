(ns metron.aws)

(def AWS (js/require "aws-sdk"))

(defn shared-ini-file-credentials [profile-name]
  (new (.-SharedIniFileCredentials AWS) #js{:profile profile-name}))

(defn set-profile! [profile]
  (let [creds (shared-ini-file-credentials profile)]
    (set! (.-credentials (.-config AWS)) creds)))

(defn set-global-region! [region]
  (.update (.-config AWS) #js{:region region}))

(defn set-logger [f] (set! (.-logger (.-config AWS)) f))

