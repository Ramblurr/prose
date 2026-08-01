(ns docs.alpha.evaluation
  (:require
   [clojure.java.io :as io]
   [fr.jeremyschoffen.prose.alpha.document.clojure :as doc]
   [fr.jeremyschoffen.prose.alpha.out.markdown.compiler :as cplr]))



(defn wrap-exception [f phase]
  (fn [& args]
    (try
      (apply f args)
      (catch Exception e
        (throw (ex-info (str "Error making the document during : " phase)
                        {:phase phase
                         :args args}
                        e))))))


(def docs-root "prose/alpha")


(defn slurp-doc* [path]
   (-> path
       io/resource
       slurp))



(def slurp-doc (wrap-exception slurp-doc* :slurp))




(def eval-doc (doc/make-evaluator {:slurp-doc slurp-doc}))


(defn document
  ([path]
   (document path {}))
  ([path input]
   (-> path
       (eval-doc (merge {:build/docs-root docs-root} input))
       :document
       cplr/compile!)))



(comment
  (slurp-doc "README.md.prose")
  (document "README.md.prose")

  (clojure.java.io/resource "docs/alpha/readme/example-1.prose")
  *e
  (def doc
    (document "README.md.prose" {:git-coord {}}))


  (spit "README-test.MD" doc))
