(ns ^{:author "Jeremy Schoffen"
      :doc "
API providing evaluation tools to evaluate documents using Clojure's environment.
"}
 fr.jeremyschoffen.prose.alpha.document.clojure
  (:require
   [clojure.java.io :as io]
   [fr.jeremyschoffen.prose.alpha.document.common.evaluator :as evaluator]
   [fr.jeremyschoffen.prose.alpha.eval.common :as eval-common]
   [fr.jeremyschoffen.prose.alpha.reader.core :as reader]))

(defn default-slurp-doc
  "Reads the resource at `path` and returns its contents."
  [path]
  (-> path
      io/resource
      slurp))

(def default-env
  "Default configuration for [[make-evaluator]]."
  {:slurp-doc default-slurp-doc
   :read-doc reader/read-from-string
   :eval-form eval
   :eval-forms eval-common/eval-forms-in-temp-ns})

(defn- ensure-namespace [namespace-symbol]
  (or (find-ns namespace-symbol)
      (let [namespace (create-ns namespace-symbol)]
        (binding [*ns* namespace]
          (refer 'clojure.core))
        namespace)))

(defn- reader-context [hidden-namespace]
  (cond-> {:aliases (into {}
                          (map (fn [[alias namespace]]
                                 [alias (ns-name namespace)]))
                          (ns-aliases *ns*))}
    (not (identical? hidden-namespace *ns*))
    (assoc :current (ns-name *ns*))))


(defn- evaluate-document*
  ([env path input opts]
   (evaluate-document* env path input opts nil))
  ([{:keys [slurp-doc read-doc eval-form eval-forms] :as env}
    path input opts inherited-context]
   (let [source (slurp-doc path)
         temporary-symbol (when-not (or inherited-context (:initial-ns opts))
                            (gensym "prose.alpha.document.temp-"))
         active-namespace (or (:namespace inherited-context)
                              (ensure-namespace (or (:initial-ns opts) temporary-symbol)))
         hidden-namespace (or (:hidden-namespace inherited-context)
                              (when temporary-symbol active-namespace))]
     (try
       (binding [*ns* active-namespace]
         (eval-common/bind-env
          {:prose.alpha.document/path path
           :prose.alpha.document/input input
           :prose.alpha.document/slurp-doc slurp-doc
           :prose.alpha.document/read-doc read-doc
           :prose.alpha.document/eval-forms eval-forms
           :prose.alpha.document/evaluate-document
           (fn [required-path]
             (evaluate-document*
              env required-path input {}
              {:namespace *ns*
               :hidden-namespace (when (identical? hidden-namespace *ns*)
                                   hidden-namespace)}))}
          (evaluator/evaluate-source
           source
           {:eval-form eval-form
            :reader-context #(reader-context hidden-namespace)})))
       (finally
         (when (and temporary-symbol (find-ns temporary-symbol))
           (remove-ns temporary-symbol)))))))

(defn evaluate-document
  "Evaluates the document at `path` one top-level item at a time.

  Each item is read using namespace state produced by earlier evaluation.
  Returns exactly `:forms` and `:document`. Evaluation performs real effects.
  `input` remains document data and is not interpreted as control options.

  Options:

  | key           | description
  | ------------- | -----------
  | `:initial-ns` | Namespace symbol used for initial reading and evaluation. |"
  ([path]
   (evaluate-document path {} {}))
  ([path input]
   (evaluate-document path input {}))
  ([path input opts]
   (evaluate-document* default-env path input opts)))

(defn make-evaluator
  "Creates a configured [[evaluate-document]] function.

  Configuration:

  | key           | description
  | ------------- | -----------
  | `:slurp-doc`  | Function that returns source text for a path. |
  | `:eval-form`  | Trusted function that evaluates one form. |
  | `:read-doc`   | Reader made available to included documents. |
  | `:eval-forms` | Evaluator made available to included documents. |

  The returned function accepts `path`, optional document `input`, and optional
  document options as separate arguments."
  ([]
   (make-evaluator {}))
  ([env]
   (let [env (merge default-env env)]
     (fn evaluate-document
       ([path]
        (evaluate-document* env path {} {}))
       ([path input]
        (evaluate-document* env path input {}))
       ([path input opts]
        (evaluate-document* env path input opts))))))

(comment
  (def eval-doc (make-evaluator))

  (eval-doc "complex-doc/master.prose"))
