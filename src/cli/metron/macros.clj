(ns metron.macros)

(defmacro with-promise [name & body]
  `(let [~name (~'cljs.core.async/promise-chan)]
     ~@body
     ~name))

(defmacro edn-res-chan
  "Wraps an async call in a channel. Form arg should be a node interop sexp but
   lacking the trailing callback argument. Return channels always receive vectors,
   either [err] or [err data].
   datafn is an optional function to call on data before its placed into channel"
  [form]
  (let [cb `(fn [~'err ~'ok]
              (if ~'err
                (~'cljs.core.async/put! ~'c [(cljs.core/js->clj ~'err :keywordize-keys true)])
                (~'cljs.core.async/put! ~'c [nil (cljs.core/js->clj ~'ok :keywordize-keys true)])))]
    `(let [~'c (~'cljs.core.async/promise-chan)]
       (~@form ~cb)
       ~'c)))