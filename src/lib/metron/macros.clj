(ns metron.macros)

(defmacro with-promise [name & body]
  `(let [~name (~'cljs.core.async/promise-chan)]
     ~@body
     ~name))

(defmacro edn-res-chan [form]
  (let [cb `(fn [~'err ~'ok]
              (if ~'err
                (~'cljs.core.async/put! ~'c [(cljs.core/js->clj ~'err :keywordize-keys true)])
                (~'cljs.core.async/put! ~'c [nil (cljs.core/js->clj ~'ok :keywordize-keys true)])))]
    `(let [~'c (~'cljs.core.async/promise-chan)]
       (~@form ~cb)
       ~'c)))