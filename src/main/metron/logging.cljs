(ns metron.logging
  (:require [clojure.string :as string]))

(defn pad-number [num]
  (str (if (< num 10) "0" "") num))

(defn timestamp []
  (let [date (js/Date.)
        year (.getFullYear date)
        month (-> (.getMonth date) inc)
        day (.getDate date)
        hours (.getHours date)
        minutes (.getMinutes date)
        seconds (.getSeconds date)
        timestamp-str (str year
                           "-" (pad-number month)
                           "-" (pad-number day)
                           " " (pad-number hours)
                           ":" (pad-number minutes)
                           ":" (pad-number seconds))]
    timestamp-str))

(defn format-arg [arg] (if (string? arg) arg (pr-str arg)))

(defn format-args [args]
  (string/join " " (map format-arg args)))

(defn stderr [arg]
  (.write (.. js/process -stderr) arg))

(defn stdout [arg]
  (.write (.. js/process -stdout) arg))

(def ^:dynamic *log* stderr)

;; TODO configure human/json/edn

(def ^:dynamic *quiet?* false)

(defn info [& args]
  (when-not *quiet?*
    (assert (fn? *log*))
    (*log* (str "["(timestamp)"] INFO: " (format-args args) \newline))))

(defn warn [& args]
  (assert (fn? *log*))
  (*log* (str "["(timestamp)"] WARN: " (format-args args) \newline)))

(defn err [& args]
  (assert (fn? *log*))
  (*log* (str "["(timestamp)"] ERROR: " (format-args args) \newline)))

(defn fatal [& args]
  (assert (fn? *log*))
  (*log* (str "["(timestamp)"] FATAL: " (format-args args) \newline)))