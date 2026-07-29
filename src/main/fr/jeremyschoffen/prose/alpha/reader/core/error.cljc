(ns fr.jeremyschoffen.prose.alpha.reader.core.error
  "Prints normalized public reader errors.")


(def ^:private separator
  "--------------------------------------------------------------------------------")


(defn- print-error* [error]
  (let [{:keys [start-index end-index
                start-line start-column
                end-line end-column
                text]} (ex-data error)]
    (println separator)
    (println (ex-message error))
    (when start-index
      (println separator)
      (println "Region: indexes" start-index "to" end-index)
      (println "line" start-line "column" start-column
               "to line" end-line "column" end-column))
    (when text
      (println separator)
      (println "Failed text:")
      (println text))
    (println separator)))


(defn print-error-msg
  "Prints `error` to standard error without exposing parser internals."
  [error]
  #?(:clj
     (binding [*out* *err*]
       (print-error* error))
     :cljs
     (binding [*print-fn* *print-err-fn*]
       (print-error* error))))


(defn handle-read-error
  "Prints normalized `error` and rethrows it."
  [error]
  (print-error-msg error)
  (throw error))
