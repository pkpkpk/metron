(ns metron.logging)

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

(defn stderr [arg]
  (.write (.. js/process -stderr) arg))

(defn stdout [arg]
  (.write (.. js/process -stdout) arg))

(def ^:dynamic *log* stderr)

;; TODO configure human/json/edn

(defn err [& args]
  (apply *log* (str "["(timestamp)"] ERROR: ") args))

(defn info [& args]
  (apply *log* (str "["(timestamp)"] INFO: ") args))

(defn warn [& args]
  (apply *log* (str "["(timestamp)"] WARN: ") args))
