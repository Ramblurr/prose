(ns fr.jeremyschoffen.prose.alpha.document.clojure-test
  (:require
    [clojure.test :refer [deftest is]]
    [fr.jeremyschoffen.prose.alpha.document.clojure :as document]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.latex.compiler :as latex]
    [fr.jeremyschoffen.prose.alpha.out.markdown.compiler :as markdown]))


(deftest inserts-required-documents
  (let [evaluated-document ((document/make-evaluator)
                            "complex-doc/master.prose")
        ns-tags (filterv #(and (map? %) (= :ns (:tag %)))
                         (tree-seq map?
                                   :content
                                   {:tag :doc
                                    :content (:document evaluated-document)}))
        first-tag (first ns-tags)]
    (is (= {:tag-count 3
            :same-parent-context? true}
           {:tag-count (count ns-tags)
            :same-parent-context?
            (every? #(= first-tag %) (rest ns-tags))}))))


(deftest compilers-consume-staged-document
  (let [source (str
                 "◊(require '[fr.jeremyschoffen.prose.alpha.document.lib :as doc])"
                 "◊(doc/xml-tag :section \"value\")")
        result ((document/make-evaluator {:slurp-doc (constantly source)})
                :memory)]
    (is (= '[(require
               '[fr.jeremyschoffen.prose.alpha.document.lib :as doc])
              (doc/xml-tag :section "value")]
           (:forms result)))
    (is (= {:html "<section>value</section>"
            :markdown "<section>value</section>"
            :latex "\\section{value}"}
           {:html (html/compile! (:document result))
            :markdown (markdown/compile! (:document result))
            :latex (latex/compile! (:document result))}))))


(deftest exposes-only-staged-evaluator-configuration
  (is (= #{:slurp-doc :eval-form}
         (set (keys document/default-env)))))


(deftest stages-reading-after-namespace-evaluation
  (let [document-ns 'prose.test.staged-document
        source (str "◊(ns prose.test.staged-document "
                    "(:require [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))"
                    "◊(vector ::local ::h/tag "
                    "#::{:current true} #::h{:alias true})")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (is (= {:forms '[(ns prose.test.staged-document
                          (:require
                            [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
                        (vector :prose.test.staged-document/local
                                :fr.jeremyschoffen.prose.alpha.out.html.tags/tag
                                #:prose.test.staged-document{:current true}
                                #:fr.jeremyschoffen.prose.alpha.out.html.tags{:alias true})]
              :document
              [nil
               [:prose.test.staged-document/local
                :fr.jeremyschoffen.prose.alpha.out.html.tags/tag
                #:prose.test.staged-document{:current true}
                #:fr.jeremyschoffen.prose.alpha.out.html.tags{:alias true}]]}
             (evaluate-document :memory {} {})))
      (finally
        (when (find-ns document-ns)
          (remove-ns document-ns))))))


(deftest uses-initial-namespace-until-a-document-namespace-supersedes-it
  (let [initial-ns 'prose.test.initial-document
        later-ns 'prose.test.later-document
        source (str "◊(vector ::initial (ns-name *ns*))"
                    "◊(ns prose.test.later-document)"
                    "◊(vector ::later (ns-name *ns*))"
                    "◊(fr.jeremyschoffen.prose.alpha.eval.common/get-env "
                    ":prose.alpha.document/input)")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (is (= {:forms '[(vector :prose.test.initial-document/initial
                              (ns-name *ns*))
                        (ns prose.test.later-document)
                        (vector :prose.test.later-document/later
                                (ns-name *ns*))
                        (fr.jeremyschoffen.prose.alpha.eval.common/get-env
                          :prose.alpha.document/input)]
              :document [[:prose.test.initial-document/initial
                          'prose.test.initial-document]
                         nil
                         [:prose.test.later-document/later
                          'prose.test.later-document]
                         {:initial-ns :ordinary-input}]}
             (evaluate-document :memory
                                {:initial-ns :ordinary-input}
                                {:initial-ns initial-ns})))
      (finally
        (doseq [namespace-symbol [initial-ns later-ns]]
          (when (find-ns namespace-symbol)
            (remove-ns namespace-symbol)))))))

(deftest reflects-require-alias-and-in-ns-before-the-next-item
  (let [later-ns 'prose.test.in-ns-document
        source (str "◊(require '[clojure.string :as str] '[clojure.set])"
                    "◊(clojure.core/vector ::str/key)"
                    "◊(alias 'sets 'clojure.set)"
                    "◊(clojure.core/vector ::sets/key)"
                    "◊(in-ns 'prose.test.in-ns-document)"
                    "◊(clojure.core/vector ::local)")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (let [result (evaluate-document :memory {} {})]
        (is (= {:forms '[(require '[clojure.string :as str] '[clojure.set])
                          (clojure.core/vector :clojure.string/key)
                          (alias 'sets 'clojure.set)
                          (clojure.core/vector :clojure.set/key)
                          (in-ns 'prose.test.in-ns-document)
                          (clojure.core/vector :prose.test.in-ns-document/local)]
                :document [nil
                           [:clojure.string/key]
                           nil
                           [:clojure.set/key]
                           (find-ns later-ns)
                           [:prose.test.in-ns-document/local]]}
               result)))
      (finally
        (when (find-ns later-ns)
          (remove-ns later-ns))))))

(deftest reads-an-outer-form-before-applying-its-namespace-change
  (let [initial-ns 'prose.test.outer-document
        nested-ns 'prose.test.nested-transition
        source (str "◊(vector (in-ns 'prose.test.nested-transition) ::same-form)"
                    "◊(clojure.core/vector ::next-form)")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (let [result (evaluate-document :memory {} {:initial-ns initial-ns})]
        (is (= {:forms '[(vector (in-ns 'prose.test.nested-transition)
                                 :prose.test.outer-document/same-form)
                          (clojure.core/vector
                            :prose.test.nested-transition/next-form)]
                :document [[(find-ns nested-ns)
                            :prose.test.outer-document/same-form]
                           [:prose.test.nested-transition/next-form]]}
               result)))
      (finally
        (doseq [namespace-symbol [initial-ns nested-ns]]
          (when (find-ns namespace-symbol)
            (remove-ns namespace-symbol)))))))


(def evaluation-effects (atom []))

(defn- thrown-error [f]
  (try
    (f)
    (catch Exception error
      error)))

(deftest scans-the-complete-structure-before-evaluating
  (let [source (str "◊(swap! fr.jeremyschoffen.prose.alpha.document.clojure-test/"
                    "evaluation-effects conj :should-not-run)"
                    "◊outer{")
        failure-start (.lastIndexOf source "{")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})
        caller-ns *ns*
        namespaces-before (set (all-ns))
        _ (reset! evaluation-effects [])
        error (thrown-error #(evaluate-document :memory {} {}))]
    (is (= {:error {:phase :structural-scan
                    :source source
                    :text "{"
                    :start-index failure-start
                    :end-index (count source)
                    :index (count source)
                    :line 1
                    :column (inc (count source))
                    :forms []
                    :document []}
            :effects []
            :caller-restored? true
            :namespaces-restored? true}
           {:error (select-keys (ex-data error)
                                [:phase :source :text :start-index :end-index
                                 :index :line :column :forms :document])
            :effects @evaluation-effects
            :caller-restored? (identical? caller-ns *ns*)
            :namespaces-restored? (= namespaces-before (set (all-ns)))}))))

(deftest retains-progress-and-effects-after-a-later-read-failure
  (let [successful-text
        "◊(swap! fr.jeremyschoffen.prose.alpha.document.clojure-test/evaluation-effects conj :before-read)"
        failed-text "◊(clojure.core/identity ::missing)"
        source (str successful-text failed-text)
        failure-start (count successful-text)
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})
        _ (reset! evaluation-effects [])
        error (thrown-error #(evaluate-document :memory {} {}))
        data (ex-data error)]
    (is (= {:phase :read
            :source source
            :text failed-text
            :start-index failure-start
            :end-index (count source)
            :index failure-start
            :line 1
            :column (inc failure-start)
            :forms '[(swap!
                       fr.jeremyschoffen.prose.alpha.document.clojure-test/evaluation-effects
                       conj
                       :before-read)]
            :document [[:before-read]]
            :effects [:before-read]}
           (assoc (select-keys data
                               [:phase :source :text :start-index :end-index
                                :index :line :column :forms :document])
                  :effects @evaluation-effects)))
    (is (not (re-find #"prose\\.alpha\\.document\\.temp-"
                      (pr-str data))))))

(deftest reports-evaluation-failures-with-form-source-and-partial-progress
  (let [successful-text
        "◊(swap! fr.jeremyschoffen.prose.alpha.document.clojure-test/evaluation-effects conj :before-eval)"
        failed-text "◊(throw (ex-info \"boom\" {:kind :expected}))"
        source (str successful-text failed-text)
        failure-start (count successful-text)
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})
        caller-ns *ns*
        namespaces-before (set (all-ns))
        _ (reset! evaluation-effects [])
        error (thrown-error #(evaluate-document :memory {} {}))]
    (is (= {:error {:phase :evaluation
                    :source source
                    :text failed-text
                    :form '(throw (ex-info "boom" {:kind :expected}))
                    :source-region {:start-index failure-start
                                    :end-index (count source)
                                    :start-line 1
                                    :start-column (inc failure-start)
                                    :end-line 1
                                    :end-column (inc (count source))}
                    :forms '[(swap!
                               fr.jeremyschoffen.prose.alpha.document.clojure-test/evaluation-effects
                               conj
                               :before-eval)
                              (throw (ex-info "boom" {:kind :expected}))]
                    :document [[:before-eval]]}
            :effects [:before-eval]
            :cause-data {:kind :expected}
            :caller-restored? true
            :namespaces-restored? true}
           {:error (select-keys (ex-data error)
                                [:phase :source :text :form :source-region
                                 :forms :document])
            :effects @evaluation-effects
            :cause-data (ex-data (ex-cause error))
            :caller-restored? (identical? caller-ns *ns*)
            :namespaces-restored? (= namespaces-before (set (all-ns)))}))))

(deftest retains-text-nil-results-and-real-effects
  (let [source
        (str "before"
             "◊(do "
             "(swap! fr.jeremyschoffen.prose.alpha.document.clojure-test/"
             "evaluation-effects conj :success) "
             "nil)"
             "after")
        evaluated-forms (atom [])
        evaluate-document
        (document/make-evaluator
          {:slurp-doc (constantly source)
           :eval-form (fn [form]
                        (swap! evaluated-forms conj form)
                        (eval form))})
        _ (reset! evaluation-effects [])
        result (evaluate-document :memory {} {})]
    (is (= {:result
            {:forms
             '["before"
               (do
                 (swap!
                   fr.jeremyschoffen.prose.alpha.document.clojure-test/evaluation-effects
                   conj
                   :success)
                 nil)
               "after"]
             :document ["before" nil "after"]}
            :effects [:success]
            :matching-counts? true
            :same-form-objects? true}
           {:result result
            :effects @evaluation-effects
            :matching-counts? (= (count (:forms result))
                                 (count (:document result)))
            :same-form-objects?
            (every? true?
                    (map identical? (:forms result) @evaluated-forms))}))))

(deftest evaluates-and-compiles-a-namespace-aware-nested-html-list
  (let [document-ns 'prose.test.html-document
        source
        (str "◊(ns prose.test.html-document "
             "(:require [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))"
             "◊h/ul[{:data-list ::list :data-tag ::h/tag}]"
             "{◊h/li{One}◊h/li{One plus two is ◊(+ 1 2)}}")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (let [result (evaluate-document :memory {} {})]
        (is (= {:result
                {:forms
                 '[(ns prose.test.html-document
                     (:require
                       [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
                   (h/ul
                     {:data-list :prose.test.html-document/list
                      :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                     (h/li "One")
                     (h/li "One plus two is " (+ 1 2)))]
                 :document
                 [nil
                  {:tag :ul
                   :attrs
                   {:data-list :prose.test.html-document/list
                    :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                   :content
                   [{:tag :li
                     :content ["One"]
                     :type :tag}
                    {:tag :li
                     :content ["One plus two is " 3]
                     :type :tag}]
                   :type :tag}]}
                :html
                (str "<ul data-list=\":prose.test.html-document/list\" "
                     "data-tag=\":fr.jeremyschoffen.prose.alpha.out.html.tags/tag\">"
                     "<li>One</li><li>One plus two is 3</li></ul>")}
               {:result result
                :html (html/compile! (:document result))})))
      (finally
        (when (find-ns document-ns)
          (remove-ns document-ns))))))

(deftest resolves-namespace-syntax-inside-recursively-nested-command
  (let [document-ns 'prose.test.nested-command
        source
        (str "◊(ns prose.test.nested-command "
             "(:require [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))"
             "◊h/ul{◊h/li[{:data-local ::inside :data-tag ::h/tag}]{Item}}")
        evaluate-document (document/make-evaluator
                           {:slurp-doc (constantly source)})]
    (try
      (let [result (evaluate-document :memory)]
        (is (= {:forms
                '[(ns prose.test.nested-command
                    (:require
                      [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
                  (h/ul
                    (h/li
                      {:data-local :prose.test.nested-command/inside
                       :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                      "Item"))]
                :nested-attrs
                {:data-local :prose.test.nested-command/inside
                 :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}}
               {:forms (:forms result)
                :nested-attrs
                (get-in result [:document 1 :content 0 :attrs])})))
      (finally
        (when (find-ns document-ns)
          (remove-ns document-ns))))))


(deftest preserves-trusted-require-macro-and-java-interop
  (let [document-ns 'prose.test.trusted-document
        source
        (str "◊(ns prose.test.trusted-document "
             "(:require "
             "[fr.jeremyschoffen.prose.alpha.document.lib :refer [def-s]]))"
             "◊(def-s answer (.toUpperCase \"trusted\"))"
             "◊(when (= answer \"TRUSTED\") "
             "[answer (.getName java.lang.String)])")
        evaluate-document (document/make-evaluator
                            {:slurp-doc (constantly source)})]
    (try
      (is (= {:forms
              '[(ns prose.test.trusted-document
                  (:require
                    [fr.jeremyschoffen.prose.alpha.document.lib
                     :refer
                     [def-s]]))
                (def-s answer (.toUpperCase "trusted"))
                (when (= answer "TRUSTED")
                  [answer (.getName java.lang.String)])]
              :document [nil "" ["TRUSTED" "java.lang.String"]]}
             (evaluate-document :memory {} {})))
      (finally
        (when (find-ns document-ns)
          (remove-ns document-ns))))))
