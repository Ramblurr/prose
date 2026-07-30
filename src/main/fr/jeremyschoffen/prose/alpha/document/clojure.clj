(ns ^{:author "Jeremy Schoffen"
      :doc "
API providing evaluation tools to evaluate documents using Clojure's environment.
"}
 fr.jeremyschoffen.prose.alpha.document.clojure
  (:require
   [clojure.java.io :as io]
   [fr.jeremyschoffen.prose.alpha.document.common.evaluator :as evaluator]
   [fr.jeremyschoffen.prose.alpha.eval.common :as eval-common]))

(defn default-slurp-doc
  "Reads the resource at `path` and returns its contents."
  [path]
  (-> path
      io/resource
      slurp))

(def default-env
  "Default configuration for [[make-evaluator]]."
  {:slurp-doc default-slurp-doc
   :eval-form eval})

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
  [{:keys [env path opts inherited-context] :as request}]
  (let [{:keys [slurp-doc eval-form]} env
        source (slurp-doc path)
        temporary-symbol (when-not (or inherited-context (:initial-ns opts))
                           (gensym "prose.alpha.document.temp-"))
        active-namespace (or (:namespace inherited-context)
                             (ensure-namespace (or (:initial-ns opts) temporary-symbol)))
        hidden-namespace (or (:hidden-namespace inherited-context)
                             (when temporary-symbol active-namespace))]
    (try
      (binding [*ns* active-namespace]
        (eval-common/bind-env
         (evaluator/document-environment
          request
          (fn [required-path]
            (evaluate-document*
             (assoc request
                    :path required-path
                    :opts {}
                    :inherited-context
                    {:namespace *ns*
                     :hidden-namespace
                     (when (identical? hidden-namespace *ns*)
                       hidden-namespace)}))))
         (evaluator/evaluate-source
          source
          {:eval-form eval-form
           :reader-context #(reader-context hidden-namespace)})))
      (finally
        (when (and temporary-symbol (find-ns temporary-symbol))
          (remove-ns temporary-symbol))))))

(def evaluate-document
  "Evaluates the document at `path` one top-level item at a time.

  Each item is read using namespace state produced by earlier evaluation.
  Returns exactly `:forms` and `:document`. Evaluation performs real effects.
  `input` remains document data and is not interpreted as control options.

  Options:

  | key           | description
  | ------------- | -----------
  | `:initial-ns` | Namespace symbol used for initial reading and evaluation. |"
  (evaluator/make-evaluate-document default-env evaluate-document*))

(defn make-evaluator
  "Creates a configured [[evaluate-document]] function.

  Configuration:

  | key          | description
  | ------------ | -----------
  | `:slurp-doc` | Function that returns source text for a path. |
  | `:eval-form` | Trusted function that evaluates one form. |

  The returned function accepts `path`, optional document `input`, and optional
  document options as separate arguments."
  ([]
   (make-evaluator {}))
  ([env]
   (-> (merge default-env env)
       (evaluator/make-evaluate-document evaluate-document*))))

(comment
  (def eval-doc (make-evaluator))

  (eval-doc "complex-doc/master.prose"))
