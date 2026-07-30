(ns ^{:author "Jeremy Schoffen"
      :doc "
Shared staged document evaluation.
"}
  fr.jeremyschoffen.prose.alpha.document.common.evaluator
  (:require
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]))


(defn- evaluation-error [source progress form error]
  (ex-info "Error during document evaluation."
           (merge {:phase :evaluation
                   :source source
                   :text (reader/form->text form source)
                   :form form
                   :source-region
                   (-> form
                       meta
                       :fr.jeremyschoffen.prose.alpha.reader.core/parse-region)}
                  @progress)
           error))

(defn- read-error [error progress]
  (let [data (ex-data error)]
    (ex-info (ex-message error)
             (merge data
                    {:phase (or (:phase data) :read)}
                    @progress)
             error)))

(defn document-environment
  "Builds the dynamic document environment shared by evaluator adapters.

  `request` supplies the configured environment, path, and input.
  `evaluate-document` recursively evaluates a required document."
  [{:keys [env path input]} evaluate-document]
  {:prose.alpha.document/path path
   :prose.alpha.document/input input
   :prose.alpha.document/slurp-doc (:slurp-doc env)
   :prose.alpha.document/evaluate-document evaluate-document})

(defn make-evaluate-document
  "Creates a three-arity document function around `evaluate-request`.

  `evaluate-request` receives one map containing the configured `env`, `path`,
  document `input`, and evaluation `opts`."
  [env evaluate-request]
  (fn evaluate-document
    ([path]
     (evaluate-request {:env env :path path :input {} :opts {}}))
    ([path input]
     (evaluate-request {:env env :path path :input input :opts {}}))
    ([path input opts]
     (evaluate-request {:env env :path path :input input :opts opts}))))

(defn evaluate-source
  "Evaluates the top-level items in `source` using an evaluator adapter.

  Adapter:

  | key               | description
  | ----------------- | -----------
  | `:eval-form`      | Function that evaluates one ordinary Clojure form. |
  | `:reader-context` | Zero-argument function returning current namespace and aliases. |

  Returns exactly `:forms` and `:document`, retaining partial progress in
  exception data when reading or evaluation fails."
  [source {:keys [eval-form reader-context]}]
  (let [progress (volatile! {:forms [] :document []})]
    (try
      (-> (reader/reduce-top-level
           source
           {:reader-context reader-context}
           (fn [state form]
             (let [state (update state :forms conj form)]
               (vreset! progress state)
               (try
                 (let [state (update state :document conj (eval-form form))]
                   (vreset! progress state)
                   state)
                 (catch #?@(:clj [Exception error] :cljs [js/Error error])
                   (throw (evaluation-error source progress form error))))))
           @progress)
          :result)
      (catch #?@(:clj [Exception error] :cljs [js/Error error])
        (if (= :evaluation (:phase (ex-data error)))
          (throw error)
          (throw (read-error error progress)))))))
