(ns fr.jeremyschoffen.prose.alpha.reader.clojurizer
  "Turns Prose parse nodes into evaluator-neutral Clojure data."
  (:require
    [clojure.walk :as walk]
    [edamame.core :as eda]
    [medley.core :as medley]))


(def ^:dynamic *parse-region* {})


(def ^:dynamic *reader-options* {:deref true
                                 :fn true
                                 :quote true
                                 :read-eval false
                                 :regex true
                                 :syntax-quote true
                                 :var true
                                 :read-cond :preserve})


(defn read-string*
  "Wraps Edamame's `parse-string` function for use in the Prose reader."
  [s]
  (try
    (eda/parse-string s *reader-options*)
    (catch #?@(:clj [Exception e] :cljs [js/Error e])
      (throw
        (ex-info "Reader failure."
                 {:type ::clojure-reader-error
                  :text s
                  :region *parse-region*
                  :failure e})))))


(declare clojurize)


(defn extract-tags
  "Replaces tags with unique symbols and maps those symbols to the replaced tag data."
  [content]
  (let [env (volatile! (transient {}))
        form (volatile! (transient []))]
    (doseq [v content]
      (if (string? v)
        (vswap! form conj! v)
        (let [sym (gensym "tag")]
          (vswap! env assoc! sym v)
          (vswap! form conj! (str " " sym " ")))))
    {:env (-> env deref persistent!)
     :form (-> form
               deref
               persistent!
               (->> (apply str)))}))


(defn inject-clojurized-tags
  "Replaces placeholder symbols in `form` with clojurized tag data from `env`."
  [form env]
  (walk/prewalk (fn [v]
                  (if-let [t (and (symbol? v)
                                  (get env v))]
                    (clojurize t)
                    v))
                form))


(defn clojurize-mixed
  "Reads mixed Clojure text and nested Prose nodes as Clojure data."
  [content]
  (let [{:keys [env form]} (extract-tags content)
        form (read-string* form)]
    (inject-clojurized-tags form env)))


(defn add-type [x t]
  (vary-meta x assoc
             :fr.jeremyschoffen.prose.alpha.reader.core/type
             t))


(defmulti clojurize* :tag)


(defmethod clojurize* :default [node]
  (throw (ex-info "Unknown parse result." {:tag node})))


(defmethod clojurize* :doc [node]
  (mapv clojurize (:content node)))


(defmethod clojurize* :symbol-use [node]
  (-> node :content first read-string*))


(defmethod clojurize* :clojure-call [node]
  (-> node :content clojurize-mixed))


(defmethod clojurize* :tag [node]
  (->> node
       :content
       (into [] (mapcat clojurize))
       seq))


(defmethod clojurize* :tag-unspliced [node]
  (->> node
       :content
       (into [] (map clojurize))
       seq))


(defmethod clojurize* :tag-name [node]
  (-> node :content first read-string* vector))


(defmethod clojurize* :tag-clj-arg [node]
  (add-type (-> node
                :content
                clojurize-mixed)
            :tag-clj-arg))


(defmethod clojurize* :tag-text-arg [node]
  (add-type (->> node
                 :content
                 rest
                 butlast
                 (mapv clojurize))
            :tag-text-arg))


(defn- add-parse-region-meta [form region]
  (->> region
       (medley/map-keys #(-> % name keyword))
       (vary-meta form assoc
                  :fr.jeremyschoffen.prose.alpha.reader.core/parse-region)))


(defn clojurize
  "Turns a Prose parse tree into evaluator-neutral Clojure data.

  Non-text forms retain their parser-supplied source region in metadata."
  [form]
  (if (string? form)
    form
    (binding [*parse-region* (meta form)]
      (let [res (clojurize* form)]
        (cond-> res
          (not (string? res))
          (add-parse-region-meta *parse-region*))))))
