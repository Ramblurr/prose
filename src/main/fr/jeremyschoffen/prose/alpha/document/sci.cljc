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
  [{:keys [env path opts inherited-context] :as request}]
  (let [{:keys [sci-ctxt slurp-doc eval-form]} env
        source (slurp-doc path)
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
         (evaluator/document-environment
          request
          (fn [required-path]
            (let [context (namespace-context sci-ctxt)]
              (evaluate-document*
               (assoc request
                      :path required-path
                      :opts {}
                      :inherited-context
                      {:hidden-namespace
                       (when (= hidden-namespace (:current context))
                         hidden-namespace)})))))
         (evaluator/evaluate-source
          source
          {:eval-form eval-form
           :reader-context #(reader-context sci-ctxt hidden-namespace)}))
        (finally
          (when temporary-symbol
            (sci/eval-form
             sci-ctxt
             (list 'remove-ns (list 'quote temporary-symbol)))))))))

(defn make-evaluator
  "Creates a configured staged SCI document evaluator.

  Configuration:

  | key          | description
  | ------------ | -----------
  | `:sci-ctxt`  | SCI context; defaults to a fresh context from [[init]]. |
  | `:slurp-doc` | Function that returns source text for a path. |
  | `:eval-form` | Function that evaluates one form in `:sci-ctxt`. |

  The returned function accepts `path`, optional document `input`, and optional
  document options as separate arguments. It returns exactly `:forms` and
  `:document`. This continues to rely on `sci/binding`; the known
  Babashka-hosted SCI binding limitation is not changed by this evaluator."
  ([]
   (make-evaluator {}))
  ([env]
   (let [sci-ctxt (or (:sci-ctxt env) (init {}))
         env (merge {:eval-form (partial sci/eval-form sci-ctxt)}
                    env
                    {:sci-ctxt sci-ctxt})]
     (evaluator/make-evaluate-document env evaluate-document*))))

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
)
