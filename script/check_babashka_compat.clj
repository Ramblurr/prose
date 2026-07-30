(ns script.check-babashka-compat
  (:require
    [clojure.test :refer [deftest is run-tests]]
    [fr.jeremyschoffen.prose.alpha.document.lib :as lib]
    [fr.jeremyschoffen.prose.alpha.document.sci :as document]
    [fr.jeremyschoffen.prose.alpha.document.sci.bindings :as bindings]
    [fr.jeremyschoffen.prose.alpha.eval.common :as eval-common]
    [fr.jeremyschoffen.prose.alpha.eval.sci :as eval-sci]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.html.tags :as html-tags]
    [fr.jeremyschoffen.prose.alpha.out.latex.compiler :as latex]
    [fr.jeremyschoffen.prose.alpha.out.markdown.compiler :as markdown]
    [fr.jeremyschoffen.prose.alpha.out.markdown.tags :as markdown-tags]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]
    [sci.core :as sci]))


(defn- thrown-error [f]
  (try
    (f)
    (catch Exception error
      error)))


(deftest compatible-modules
  (is (= {:babashka-version "1.12.218"
          :evaluation [3]
          :document-tag {:tag :strong
                         :content ["42"]
                         :type :tag}
          :namespace-bindings? true
          :html "<strong>42</strong>"
          :markdown "```text\n42\n```"
          :latex "\\strong{42}"}
         {:babashka-version (System/getProperty "babashka.version")
          :evaluation (eval-common/eval-forms '[(+ 1 2)])
          :document-tag (lib/xml-tag :strong "42")
          :namespace-bindings? (contains?
                                (bindings/make-ns-bindings
                                 fr.jeremyschoffen.prose.alpha.document.lib)
                                'fr.jeremyschoffen.prose.alpha.document.lib)
          :html (html/compile! [(html-tags/strong "42")])
          :markdown (markdown/compile! [(markdown-tags/code-block "42")])
          :latex (latex/compile! [(lib/xml-tag :strong "42")])})))


(deftest public-reader-path
  (let [named-source "Some text. ◊div[{:class ◊str{c1 c2}}] { ◊def[x 1] ◊(def y 2) }"
        named-forms (reader/read-from-string named-source)
        error-data (try
                     (reader/read-from-string "line one\nbefore ◊")
                     (catch Exception error
                       (select-keys (ex-data error)
                                    [:type :text :index :line :column :expected])))]
    (is (= {:forms '["Unicode α\nbefore "
                      (vector some.ns/value (inc 1) 42)
                      " and "
                      "literal ◊ text"]
            :named-forms '["Some text. "
                           (div {:class (str "c1 c2")}
                                " " (def x 1) " " (def y 2) " ")]
            :source-text "◊div[{:class ◊str{c1 c2}}] { ◊def[x 1] ◊(def y 2) }"
            :source-region {:start-index 11
                            :end-index 62
                            :start-line 1
                            :start-column 12
                            :end-line 1
                            :end-column 63}
            :error-data
            {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/syntax-error
             :text "◊"
             :index 17
             :line 2
             :column 9
             :expected "a command"}
            :reader-options '[(:example.ns/value)]
            :initial-ns '[(vector :bb.document/local
                                  #:bb.document{:key 1})
                          (outer :alias.target/value
                                 (inner :bb.document/nested))]
            :parser-libraries-loaded? false}
           {:forms (reader/read-from-string
                    "Unicode α\nbefore ◊(vector ◊|some.ns/value ◊(inc 1) ◊\"42\") and ◊\"literal ◊ text\"")
            :named-forms named-forms
            :source-text (reader/form->text (second named-forms) named-source)
            :source-region
            (-> named-forms
                second
                meta
                :fr.jeremyschoffen.prose.alpha.reader.core/parse-region)
            :error-data error-data
            :reader-options (reader/read-from-string
                             "◊(::alias/value)"
                             {:reader-options
                              {:auto-resolve {'alias 'example.ns}}})
            :initial-ns (reader/read-from-string
                          (str "◊(vector ::local #::{:key 1})"
                               "◊outer[::alias/value]{◊inner[::nested]}")
                          {:initial-ns 'bb.document
                           :reader-options
                           {:auto-resolve {:current 'ignored.document
                                           'alias 'alias.target}}})
            :parser-libraries-loaded?
            (boolean
             (some (fn [namespace]
                     (re-find #"^(instaparse|lambdaisland\.regal)"
                              (str (ns-name namespace))))
                   (all-ns)))}))))


(deftest public-staged-sci-path
  (let [success-source "Hello, ◊strong{◊(str (inc 41))}!"
        failure-source
        (str "◊(ns bb.document.failure)"
             "◊(throw (ex-info \"boom\" {:kind :expected}))")
        sources {:success success-source
                 :failure failure-source}
        sci-ctxt
        (document/init
         {:namespaces
          {'bb.document {'strong html-tags/strong}}})
        evaluate-document
        (document/make-evaluator {:sci-ctxt sci-ctxt
                                  :slurp-doc sources})
        caller-namespace @sci/ns
        success (evaluate-document
                 :success {} {:initial-ns 'bb.document})
        restored-after-success?
        (identical? caller-namespace @sci/ns)
        failure (thrown-error
                 #(evaluate-document
                   :failure {} {:initial-ns 'bb.document}))
        restored-after-failure?
        (identical? caller-namespace @sci/ns)
        recovery (evaluate-document
                  :success {} {:initial-ns 'bb.document})
        expected-success
        {:forms '["Hello, " (strong (str (inc 41))) "!"]
         :document ["Hello, "
                    {:tag :strong
                     :content ["42"]
                     :type :tag}
                    "!"]}]
    (is (= {:success expected-success
            :html "Hello, <strong>42</strong>!"
            :restored-after-success? true
            :failure
            {:phase :evaluation
             :text "◊(throw (ex-info \"boom\" {:kind :expected}))"
             :form '(throw (ex-info "boom" {:kind :expected}))
             :forms '[(ns bb.document.failure)
                      (throw (ex-info "boom" {:kind :expected}))]
             :document [nil]}
            :failure-cause {:kind :expected}
            :restored-after-failure? true
            :recovery expected-success
            :restored-after-recovery? true}
           {:success success
            :html (html/compile! (:document success))
            :restored-after-success? restored-after-success?
            :failure
            (select-keys (ex-data failure)
                         [:phase :text :form :forms :document])
            :failure-cause (ex-data (ex-cause failure))
            :restored-after-failure? restored-after-failure?
            :recovery recovery
            :restored-after-recovery?
            (identical? caller-namespace @sci/ns)}))))


(deftest recursive-staged-sci-paths
  (let [success-parent-source
        (str "◊(ns bb.recursive.parent "
             "(:require [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))"
             "◊(vector ::before (ns-name *ns*))"
             "◊doc/require-doc {child}"
             "◊(vector ::after (ns-name *ns*))")
        success-child-source
        (str "◊(vector ::inherited (ns-name *ns*))"
             "◊(ns bb.recursive.child)"
             "◊(vector ::changed (ns-name *ns*))")
        failure-parent-prefix
        (str "◊(ns bb.recursive.failure-parent "
             "(:require [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))"
             "◊(vector ::before (ns-name *ns*))")
        failure-form-text
        (str "◊(try "
             "(doc/require-doc \"failing-child\") "
             "(catch Exception error "
             "(throw (ex-info \"parent observed child failure\" "
             "{:parent-ns (ns-name *ns*) "
             ":parent-keyword ::restored} error))))")
        failure-parent-source (str failure-parent-prefix failure-form-text)
        failure-child-prefix
        (str "◊(vector ::before (ns-name *ns*))"
             "◊(ns bb.recursive.failure-child)")
        failure-child-text
        "◊(throw (ex-info \"child boom\" {:kind :nested}))"
        failure-child-source (str failure-child-prefix failure-child-text)
        sources {:success-parent success-parent-source
                 "child" success-child-source
                 :failure-parent failure-parent-source
                 "failing-child" failure-child-source}
        sci-ctxt (document/init {})
        evaluate-document
        (document/make-evaluator {:sci-ctxt sci-ctxt
                                  :slurp-doc sources})
        caller-namespace @sci/ns
        failure (thrown-error #(evaluate-document :failure-parent))
        restored-after-failure? (identical? caller-namespace @sci/ns)
        failure-chain (vec (take-while some? (iterate ex-cause failure)))
        recovery (evaluate-document :success-parent)
        chain-keys [:phase :source :text :form :source-region
                    :forms :document :parent-ns :parent-keyword :path :kind]]
    (is
     (= {:failure-messages
         ["Error during document evaluation."
          "parent observed child failure"
          "Error requiring doc."
          "Error during document evaluation."
          "child boom"]
         :failure-data
         [{:phase :evaluation
           :source failure-parent-source
           :text failure-form-text
           :form
           '(try
              (doc/require-doc "failing-child")
              (catch Exception error
                (throw
                 (ex-info
                  "parent observed child failure"
                  {:parent-ns (ns-name *ns*)
                   :parent-keyword :bb.recursive.failure-parent/restored}
                  error))))
           :source-region
           {:start-index (count failure-parent-prefix)
            :end-index (count failure-parent-source)
            :start-line 1
            :start-column (inc (count failure-parent-prefix))
            :end-line 1
            :end-column (inc (count failure-parent-source))}
           :forms
           '[(ns bb.recursive.failure-parent
               (:require
                [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))
             (vector :bb.recursive.failure-parent/before (ns-name *ns*))
             (try
               (doc/require-doc "failing-child")
               (catch Exception error
                 (throw
                  (ex-info
                   "parent observed child failure"
                   {:parent-ns (ns-name *ns*)
                    :parent-keyword :bb.recursive.failure-parent/restored}
                   error))))]
           :document [nil
                      [:bb.recursive.failure-parent/before
                       'bb.recursive.failure-parent]]}
          {:parent-ns 'bb.recursive.failure-parent
           :parent-keyword :bb.recursive.failure-parent/restored}
          {:path "failing-child"}
          {:phase :evaluation
           :source failure-child-source
           :text failure-child-text
           :form '(throw (ex-info "child boom" {:kind :nested}))
           :source-region
           {:start-index (count failure-child-prefix)
            :end-index (count failure-child-source)
            :start-line 1
            :start-column (inc (count failure-child-prefix))
            :end-line 1
            :end-column (inc (count failure-child-source))}
           :forms
           '[(vector :bb.recursive.failure-parent/before (ns-name *ns*))
             (ns bb.recursive.failure-child)
             (throw (ex-info "child boom" {:kind :nested}))]
           :document
           [[:bb.recursive.failure-parent/before
             'bb.recursive.failure-parent]
            nil]}
          {:kind :nested}]
         :restored-after-failure? true
         :recovery
         {:forms
          '[(ns bb.recursive.parent
              (:require
               [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))
            (vector :bb.recursive.parent/before (ns-name *ns*))
            (doc/require-doc "child")
            (vector :bb.recursive.parent/after (ns-name *ns*))]
          :document
          [nil
           [:bb.recursive.parent/before 'bb.recursive.parent]
           {:tag :<>
            :attrs {}
            :content
            [[:bb.recursive.parent/inherited 'bb.recursive.parent]
             nil
             [:bb.recursive.child/changed 'bb.recursive.child]]
            :type :tag}
           [:bb.recursive.parent/after 'bb.recursive.parent]]}
         :restored-after-recovery? true}
        {:failure-messages (mapv ex-message failure-chain)
         :failure-data
         (mapv #(select-keys (ex-data %) chain-keys) failure-chain)
         :restored-after-failure? restored-after-failure?
         :recovery recovery
         :restored-after-recovery?
         (identical? caller-namespace @sci/ns)}))))


(deftest temporary-document-namespaces-are-cleaned-up
  (let [failure-source
        "◊(throw (ex-info \"temporary boom\" {:kind :temporary}))"
        sci-ctxt (document/init {})
        evaluate-document
        (document/make-evaluator
         {:sci-ctxt sci-ctxt
          :slurp-doc {:success "◊(+ 40 2)"
                      :failure failure-source}})
        caller-namespace @sci/ns
        namespace-symbols
        #(sci/eval-form sci-ctxt '(set (map ns-name (all-ns))))
        namespaces-before (namespace-symbols)
        success (evaluate-document :success)
        namespaces-after-success (namespace-symbols)
        failure (thrown-error #(evaluate-document :failure))
        namespaces-after-failure (namespace-symbols)]
    (is
     (= {:success {:forms '[(+ 40 2)]
                  :document [42]}
         :failure {:phase :evaluation
                   :source failure-source
                   :text failure-source
                   :form
                   '(throw
                     (ex-info "temporary boom" {:kind :temporary}))
                   :source-region
                   {:start-index 0
                    :end-index (count failure-source)
                    :start-line 1
                    :start-column 1
                    :end-line 1
                    :end-column (inc (count failure-source))}
                   :forms
                   '[(throw
                      (ex-info "temporary boom" {:kind :temporary}))]
                   :document []}
         :failure-cause {:kind :temporary}
         :namespace-sets-restored? true
         :caller-restored? true}
        {:success success
         :failure
         (select-keys
          (ex-data failure)
          [:phase :source :text :form :source-region :forms :document])
         :failure-cause (ex-data (ex-cause failure))
         :namespace-sets-restored?
         (= namespaces-before
            namespaces-after-success
            namespaces-after-failure)
         :caller-restored? (identical? caller-namespace @sci/ns)}))))


(deftest lower-level-sci-paths
  (let [caller-namespace @sci/ns
        success
        (eval-sci/eval-forms
         '[(ns bb.lower-level.success)
           (+ 40 2)])
        restored-after-success?
        (identical? caller-namespace @sci/ns)
        failure
        (thrown-error
         #(eval-sci/eval-forms-in-temp-ns
           '[(throw (ex-info "lower boom" {:kind :lower}))]))]
    (is (= {:success [nil 42]
            :restored-after-success? true
            :failure
            {:prose.alpha.evaluation/env
             {:prose.alpha/env :clojure-sci}
             :prose.alpha.evaluation/form
             '(throw (ex-info "lower boom" {:kind :lower}))}
            :restored-after-failure? true}
           {:success success
            :restored-after-success? restored-after-success?
            :failure (ex-data failure)
            :restored-after-failure?
            (identical? caller-namespace @sci/ns)}))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error))
    (System/exit 1)))
