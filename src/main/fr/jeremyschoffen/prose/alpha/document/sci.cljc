(ns ^{:author "Jeremy Schoffen"
      :doc "
API providing evaluation tools to evaluate document using Sci.
"}
  fr.jeremyschoffen.prose.alpha.document.sci
  (:require
    [fr.jeremyschoffen.prose.alpha.document.common.evaluator :as evaluator]
    [fr.jeremyschoffen.prose.alpha.document.sci.bindings :as sci-bindings :include-macros true]
    [fr.jeremyschoffen.prose.alpha.eval.common :as eval-common :include-macros true]
    [fr.jeremyschoffen.prose.alpha.eval.sci :as eval-sci]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]
    [medley.core :as medley]
    [sci.core :as sci :include-macros true]

    fr.jeremyschoffen.prose.alpha.document.lib))


(defmacro make-ns-bindings
  "Alias for the [[fr.jeremyschoffen.prose.alpha.document.sci.bindings/make-ns-bindings]] macro."
  [& body]
  `(sci-bindings/make-ns-bindings ~@body))


(def sci-opt-doc-ns
  "Default namespaces bindings options passed to sci when making a sci evaluation context.

  Here the [[fr.jeremyschoffen.prose.alpha.document.lib]] is made avalable from inside document by default."
  {:namespaces (sci-bindings/make-ns-bindings fr.jeremyschoffen.prose.alpha.document.lib)})


(defn init
  "Initialize a Sci evaluation context using [[fr.jeremyschoffen.prose.alpha.eval.sci/init]] and
  merging automatically [[sci-opt-doc-ns]]"
  [opts]
  (let [opts (medley/deep-merge sci-opt-doc-ns opts)]
    (eval-sci/init opts)))


(defn- namespace-context [sci-ctxt]
  (sci/eval-form
    sci-ctxt
    '(let [namespace *ns*]
       {:current (ns-name namespace)
        :aliases (into {}
                       (map (fn [[alias target]]
                              [alias (ns-name target)]))
                       (ns-aliases namespace))})))

(defn- reader-context [sci-ctxt hidden-namespace]
  (let [context (namespace-context sci-ctxt)]
    (if (= hidden-namespace (:current context))
      (dissoc context :current)
      context)))

(defn- evaluate-document*
  ([env path input opts]
   (evaluate-document* env path input opts nil))
  ([{:keys [sci-ctxt slurp-doc read-doc eval-form eval-forms] :as env}
    path input opts inherited-context]
   (let [source (slurp-doc path)
         temporary-symbol (when-not (or inherited-context (:initial-ns opts))
                            (gensym "prose.alpha.document.temp-"))
         initial-namespace (or (:initial-ns opts) temporary-symbol)
         hidden-namespace (or (:hidden-namespace inherited-context)
                              temporary-symbol)]
     (sci/binding [sci/ns @sci/ns]
       (when-not inherited-context
         (sci/eval-form sci-ctxt (list 'ns initial-namespace)))
       (try
         (eval-common/bind-env
          {:prose.alpha.document/path path
           :prose.alpha.document/input input
           :prose.alpha.document/slurp-doc slurp-doc
           :prose.alpha.document/read-doc read-doc
           :prose.alpha.document/eval-forms eval-forms
           :prose.alpha.document/evaluate-document
           (fn [required-path]
             (let [context (namespace-context sci-ctxt)]
               (evaluate-document*
                env required-path input {}
                {:hidden-namespace
                 (when (= hidden-namespace (:current context))
                   hidden-namespace)})))}
          (evaluator/evaluate-source
           source
           {:eval-form eval-form
            :reader-context #(reader-context sci-ctxt hidden-namespace)}))
         (finally
           (when temporary-symbol
             (sci/eval-form
              sci-ctxt
              (list 'remove-ns (list 'quote temporary-symbol))))))))))

(defn make-evaluator
  "Creates a configured staged SCI document evaluator.

  Configuration:

  | key           | description
  | ------------- | -----------
  | `:sci-ctxt`   | SCI context; defaults to a fresh context from [[init]]. |
  | `:slurp-doc`  | Function that returns source text for a path. |
  | `:eval-form`  | Function that evaluates one form in `:sci-ctxt`. |
  | `:read-doc`   | Reader made available to inserted documents. |
  | `:eval-forms` | Transitional forms evaluator exposed in the document environment. |

  The returned function accepts `path`, optional document `input`, and optional
  document options as separate arguments. It returns exactly `:forms` and
  `:document`. This continues to rely on `sci/binding`; the known
  Babashka-hosted SCI binding limitation is not changed by this evaluator."
  ([]
   (make-evaluator {}))
  ([env]
   (let [sci-ctxt (or (:sci-ctxt env) (init {}))
         env (merge {:read-doc reader/read-from-string
                     :eval-form (partial sci/eval-form sci-ctxt)
                     :eval-forms (partial eval-sci/eval-forms-in-temp-ns sci-ctxt)}
                    env
                    {:sci-ctxt sci-ctxt})]
     (fn evaluate-document
       ([path]
        (evaluate-document* env path {} {}))
       ([path input]
        (evaluate-document* env path input {}))
       ([path input opts]
        (evaluate-document* env path input opts))))))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(comment
  (require '[clojure.java.io :as io])

  (def ctxt (init {}))

  (-> ctxt
      :env
      deref
      :namespaces
      (get 'fr.jeremyschoffen.prose.alpha.document.lib))

  (def slurp-doc (fn [path]
                   (-> path
                       io/resource
                       slurp)))

  (def eval-doc (make-evaluator {:sci-ctxt ctxt
                                 :slurp-doc slurp-doc}))

  (eval-doc "complex-doc/master.prose")


  (eval-common/bind-env {:prose.alpha.document/input {:some :input}}
    (eval-sci/eval-forms-in-temp-ns ctxt
      '[(require '[fr.jeremyschoffen.prose.alpha.document.lib :refer [get-input]])
        (get-input)])))
