(ns fr.jeremyschoffen.prose.alpha.docs.pages.readme.example-evaluation
  (:require
    [clojure.string :as string]
    [fr.jeremyschoffen.prose.alpha.document.sci :as doc]
    [fr.jeremyschoffen.prose.alpha.document.sci.bindings :as bindings]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as cplr]


    fr.jeremyschoffen.prose.alpha.out.html.tags))


(def docs-root "src/build/fr/jeremyschoffen/prose/alpha/docs/pages/readme/")
(def example-src (str docs-root "example-doc.html.prose"))
(def example-dest (str docs-root "example-doc.html"))


;; Preparing the namespaces accessible to the sci evaluation env
(def sci-nss {:namespaces
              (bindings/make-ns-bindings
                fr.jeremyschoffen.prose.alpha.out.html.tags)})


;; Making the sci environment
(def sci-ctxt (doc/init sci-nss))


;; Putting together a staged document evaluator
(def eval-doc (doc/make-evaluator {:sci-ctxt sci-ctxt
                                   :slurp-doc slurp}))

;; Generation of the html example
(defn make-example []
  (-> example-src
      eval-doc
      :document
      cplr/compile!
      string/trim
      (->> (spit example-dest))))


(comment
  (make-example))
