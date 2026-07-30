(ns fr.jeremyschoffen.prose.alpha.document.sci-test
  (:require
    #?@(:clj [[clojure.java.io :as io]
              [clojure.test :refer [deftest is]]
              [fr.jeremyschoffen.prose.alpha.document.clojure :as clojure-document]]
        :cljs [[cljs.test :refer-macros [deftest is]]])
    [fr.jeremyschoffen.prose.alpha.document.sci :as document]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.html.tags :as tags]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]
    [sci.core :as sci :include-macros true]))


(def html-namespace
  'fr.jeremyschoffen.prose.alpha.out.html.tags)

(def namespace-source
  (str "◊(ns prose.test.shared-document "
       "(:require [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))"))

(def list-source
  (str "◊h/ul[{:data-local ::local :data-tag ::h/tag}]"
       "{◊h/li{One}◊h/li{One plus two is ◊(+ 1 2)}}"))

(def valid-source
  (str namespace-source list-source))

(def failed-source
  "◊(identity ::missing/key)")

(def expected-list
  {:tag :ul
   :attrs {:data-local :prose.test.shared-document/local
           :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
   :content [{:tag :li
              :content ["One"]
              :type :tag}
             {:tag :li
              :content ["One plus two is " 3]
              :type :tag}]
   :type :tag})

(defn- form-region [form]
  (-> form
      meta
      :fr.jeremyschoffen.prose.alpha.reader.core/parse-region))

(defn- thrown-error [f]
  (try
    (f)
    (catch #?@(:clj [Exception error] :cljs [js/Error error])
      error)))

(defn- make-sci-evaluator
  ([sources]
   (make-sci-evaluator sources {}))
  ([sources opts]
   (let [sci-ctxt (document/init opts)]
     {:sci-ctxt sci-ctxt
      :evaluate-document
      (document/make-evaluator {:sci-ctxt sci-ctxt
                                :slurp-doc sources})})))

#?(:clj
   (do
     (def resource io/resource)
     (def read-file slurp)

     (defn- evaluate-with-clojure [sources path opts]
       ((clojure-document/make-evaluator {:slurp-doc sources})
        path {} opts))

     (defn- remove-clojure-namespaces! [namespace-symbols]
       (doseq [namespace-symbol namespace-symbols]
         (when (find-ns namespace-symbol)
           (remove-ns namespace-symbol)))))

   :cljs
   (do
     (def resource
       {"complex-doc/master.prose" "test-resources/complex-doc/master.prose"
        "complex-doc/section-1.prose" "test-resources/complex-doc/section-1.prose"
        "complex-doc/section-2.prose" "test-resources/complex-doc/section-2.prose"})

     (def fs (js/require "fs"))

     (defn read-file [path]
       (str (.readFileSync fs path)))))

(defn- slurp-resource [path]
  (-> path
      resource
      read-file))


(deftest stages-namespace-aware-html
  (let [{:keys [evaluate-document]}
        (make-sci-evaluator
         (constantly valid-source)
         {:namespaces
          {html-namespace {'li tags/li
                           'ul tags/ul}}})
        caller-namespace @sci/ns
        result (evaluate-document :memory)
        namespace-end (count namespace-source)
        source-end (count valid-source)
        observation
        {:forms (:forms result)
         :regions (mapv form-region (:forms result))
         :source-texts (mapv #(reader/form->text % valid-source)
                             (:forms result))
         :document (:document result)
         :html (html/compile! (:document result))
         :matching-counts? (= (count (:forms result))
                              (count (:document result)))
         :caller-restored? (= caller-namespace @sci/ns)}]
    (is (= {:forms
            '[(ns prose.test.shared-document
                (:require
                  [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
              (h/ul
                {:data-local :prose.test.shared-document/local
                 :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                (h/li "One")
                (h/li "One plus two is " (+ 1 2)))]
            :regions
            [{:start-index 0
              :end-index namespace-end
              :start-line 1
              :start-column 1
              :end-line 1
              :end-column (inc namespace-end)}
             {:start-index namespace-end
              :end-index source-end
              :start-line 1
              :start-column (inc namespace-end)
              :end-line 1
              :end-column (inc source-end)}]
            :source-texts [namespace-source list-source]
            :document [nil expected-list]
            :html
            (str "<ul data-local=\":prose.test.shared-document/local\" "
                 "data-tag=\":fr.jeremyschoffen.prose.alpha.out.html.tags/tag\">"
                 "<li>One</li><li>One plus two is 3</li></ul>")
            :matching-counts? true
            :caller-restored? true}
           observation))
    #?(:clj
       (try
         (let [clojure-result
               (evaluate-with-clojure {:memory valid-source} :memory {})]
           (is (= (dissoc observation :caller-restored?)
                  {:forms (:forms clojure-result)
                   :regions (mapv form-region (:forms clojure-result))
                   :source-texts
                   (mapv #(reader/form->text % valid-source)
                         (:forms clojure-result))
                   :document (:document clojure-result)
                   :html (html/compile! (:document clojure-result))
                   :matching-counts?
                   (= (count (:forms clojure-result))
                      (count (:document clojure-result)))})))
         (finally
           (remove-clojure-namespaces!
            ['prose.test.shared-document]))))))

(deftest resolves-namespace-syntax-inside-recursively-nested-command
  (let [source
        (str "◊(ns prose.test.sci-nested-command "
             "(:require [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))"
             "◊h/ul{◊h/li[{:data-local ::inside :data-tag ::h/tag}]{Item}}")
        {:keys [evaluate-document]}
        (make-sci-evaluator
         (constantly source)
         {:namespaces
          {html-namespace {'li tags/li
                           'ul tags/ul}}})
        result (evaluate-document :memory)]
    (is (= {:forms
            '[(ns prose.test.sci-nested-command
                (:require
                  [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
              (h/ul
                (h/li
                  {:data-local :prose.test.sci-nested-command/inside
                   :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                  "Item"))]
            :nested-attrs
            {:data-local :prose.test.sci-nested-command/inside
             :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}}
           {:forms (:forms result)
            :nested-attrs
            (get-in result [:document 1 :content 0 :attrs])}))))


(deftest preserves-partial-progress-and-recovers-after-read-failure
  (let [invalid-source (str valid-source failed-source)
        sources {:invalid invalid-source
                 :missing "◊(identity ::local)"
                 :recovery (str "◊(ns prose.test.sci-recovery)"
                                "◊(vector ::ok (ns-name *ns*))")}
        {:keys [evaluate-document]}
        (make-sci-evaluator
         sources
         {:namespaces
          {html-namespace {'li tags/li
                           'ul tags/ul}}})
        caller-namespace @sci/ns
        invalid-error (thrown-error #(evaluate-document :invalid))
        missing-error (thrown-error #(evaluate-document :missing))
        recovery (evaluate-document :recovery)
        invalid-data (ex-data invalid-error)
        missing-data (ex-data missing-error)
        failed-start (count valid-source)]
    (is (= {:invalid
            {:phase :read
             :source invalid-source
             :text failed-source
             :start-index failed-start
             :end-index (count invalid-source)
             :index failed-start
             :line 1
             :column (inc failed-start)
             :forms
             '[(ns prose.test.shared-document
                 (:require
                   [fr.jeremyschoffen.prose.alpha.out.html.tags :as h]))
               (h/ul
                 {:data-local :prose.test.shared-document/local
                  :data-tag :fr.jeremyschoffen.prose.alpha.out.html.tags/tag}
                 (h/li "One")
                 (h/li "One plus two is " (+ 1 2)))]
             :document [nil expected-list]}
            :missing
            {:phase :read
             :text "◊(identity ::local)"
             :forms []
             :document []}
            :missing-hides-temporary-namespace? true
            :recovery
            {:forms
             '[(ns prose.test.sci-recovery)
               (vector :prose.test.sci-recovery/ok (ns-name *ns*))]
             :document [nil
                        [:prose.test.sci-recovery/ok
                         'prose.test.sci-recovery]]}
            :caller-restored? true}
           {:invalid
            (select-keys invalid-data
                         [:phase :source :text :start-index :end-index
                          :index :line :column :forms :document])
            :missing
            (select-keys missing-data [:phase :text :forms :document])
            :missing-hides-temporary-namespace?
            (not (re-find #"prose\\.alpha\\.document\\.temp-"
                          (pr-str missing-data)))
            :recovery recovery
            :caller-restored? (= caller-namespace @sci/ns)}))
    #?(:clj
       (try
         (let [clojure-error
               (thrown-error
                #(evaluate-with-clojure {:invalid invalid-source}
                                        :invalid
                                        {}))]
           (is (= (select-keys invalid-data
                               [:phase :source :text :start-index :end-index
                                :index :line :column :forms :document])
                  (select-keys (ex-data clojure-error)
                               [:phase :source :text :start-index :end-index
                                :index :line :column :forms :document]))))
         (finally
           (remove-clojure-namespaces!
            ['prose.test.shared-document]))))))


(deftest supports-initial-and-top-level-namespace-transitions
  (let [source
        (str "◊(vector ::initial (ns-name *ns*))"
             "◊(ns prose.test.sci-later)"
             "◊(vector (in-ns 'prose.test.sci-nested) ::same-form)"
             "◊(vector ::next (ns-name *ns*))")
        {:keys [evaluate-document]}
        (make-sci-evaluator (constantly source))
        caller-namespace @sci/ns
        result (evaluate-document
                :memory {} {:initial-ns 'prose.test.sci-initial})]
    (is (= {:result
            {:forms
             '[(vector :prose.test.sci-initial/initial (ns-name *ns*))
               (ns prose.test.sci-later)
               (vector
                 (in-ns 'prose.test.sci-nested)
                 :prose.test.sci-later/same-form)
               (vector :prose.test.sci-nested/next (ns-name *ns*))]
             :document
             [[:prose.test.sci-initial/initial 'prose.test.sci-initial]
              nil
              [nil :prose.test.sci-later/same-form]
              [:prose.test.sci-nested/next 'prose.test.sci-nested]]}
            :caller-restored? true}
           {:result result
            :caller-restored? (= caller-namespace @sci/ns)}))))


(deftest restores-bindings-and-progress-after-evaluation-failure
  (let [failed-text "◊(throw (ex-info \"boom\" {:kind :expected}))"
        namespace-text "◊(ns prose.test.sci-evaluation-failure)"
        failed-document (str namespace-text failed-text)
        recovery-document
        (str "◊(ns prose.test.sci-after-evaluation-failure)"
             "◊(vector ::ok (ns-name *ns*))")
        {:keys [evaluate-document]}
        (make-sci-evaluator {:failure failed-document
                             :recovery recovery-document})
        caller-namespace @sci/ns
        error (thrown-error #(evaluate-document :failure))
        recovery (evaluate-document :recovery)]
    (is (= {:failure
            {:phase :evaluation
             :source failed-document
             :text failed-text
             :form '(throw (ex-info "boom" {:kind :expected}))
             :source-region
             {:start-index (count namespace-text)
              :end-index (count failed-document)
              :start-line 1
              :start-column (inc (count namespace-text))
              :end-line 1
              :end-column (inc (count failed-document))}
             :forms
             '[(ns prose.test.sci-evaluation-failure)
               (throw (ex-info "boom" {:kind :expected}))]
             :document [nil]}
            :cause-data {:kind :expected}
            :recovery
            {:forms
             '[(ns prose.test.sci-after-evaluation-failure)
               (vector
                 :prose.test.sci-after-evaluation-failure/ok
                 (ns-name *ns*))]
             :document
             [nil
              [:prose.test.sci-after-evaluation-failure/ok
               'prose.test.sci-after-evaluation-failure]]}
            :caller-restored? true}
           {:failure
            (select-keys (ex-data error)
                         [:phase :source :text :form :source-region
                          :forms :document])
            :cause-data (ex-data (ex-cause error))
            :recovery recovery
            :caller-restored? (= caller-namespace @sci/ns)}))))


(deftest isolates-nested-and-repeated-required-documents
  (let [sources
        {:parent
         (str "◊(ns prose.test.required-parent "
              "(:require [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))"
              "◊doc/require-doc {child}"
              "◊doc/require-doc {child}"
              "◊(vector ::after (ns-name *ns*))")
         "child"
         (str "◊(vector ::child-before (ns-name *ns*))"
              "◊doc/require-doc {grandchild}"
              "◊(ns prose.test.required-child)"
              "◊(vector ::child-after (ns-name *ns*))")
         "grandchild"
         (str "◊(vector ::grandchild-before (ns-name *ns*))"
              "◊(ns prose.test.required-grandchild)"
              "◊(vector ::grandchild-after (ns-name *ns*))")}
        {:keys [evaluate-document]} (make-sci-evaluator sources)
        caller-namespace @sci/ns
        result (evaluate-document :parent)
        [_ first-required second-required after-required]
        (:document result)
        expected-required
        {:tag :<>
         :attrs {}
         :content
         [[:prose.test.required-parent/child-before
           'prose.test.required-parent]
          {:tag :<>
           :attrs {}
           :content
           [[:prose.test.required-parent/grandchild-before
             'prose.test.required-parent]
            nil
            [:prose.test.required-grandchild/grandchild-after
             'prose.test.required-grandchild]]
           :type :tag}
          nil
          [:prose.test.required-child/child-after
           'prose.test.required-child]]
         :type :tag}
        observation
        {:forms (:forms result)
         :required-copies [first-required second-required]
         :after-required after-required
         :caller-restored? (= caller-namespace @sci/ns)}]
    (is (= {:forms
            '[(ns prose.test.required-parent
                (:require
                  [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))
              (doc/require-doc "child")
              (doc/require-doc "child")
              (vector :prose.test.required-parent/after (ns-name *ns*))]
            :required-copies [expected-required expected-required]
            :after-required
            [:prose.test.required-parent/after
             'prose.test.required-parent]
            :caller-restored? true}
           observation))
    #?(:clj
       (try
         (is (= result
                (evaluate-with-clojure sources :parent {})))
         (finally
           (remove-clojure-namespaces!
            ['prose.test.required-parent
             'prose.test.required-child
             'prose.test.required-grandchild]))))))


(deftest keeps-inserted-documents-read-only
  (let [sources
        {:parent
         (str "◊(ns prose.test.insert-parent "
              "(:require [fr.jeremyschoffen.prose.alpha.document.lib :as doc]))"
              "◊doc/insert-doc {inserted}"
              "◊(vector ::after (ns-name *ns*))")
         "inserted"
         "◊(throw (ex-info \"must not run\" {:kind :inserted}))"}
        {:keys [evaluate-document]} (make-sci-evaluator sources)
        result (evaluate-document :parent)]
    (is (= {:document
            [nil
             {:tag :<>
              :attrs {}
              :content
              '[(throw (ex-info "must not run" {:kind :inserted}))]
              :type :tag}
             [:prose.test.insert-parent/after
              'prose.test.insert-parent]]}
           {:document (:document result)}))
    #?(:clj
       (try
         (is (= result
                (evaluate-with-clojure sources :parent {})))
         (finally
           (remove-clojure-namespaces!
            ['prose.test.insert-parent]))))))


(deftest inserts-and-requires-resource-documents
  (let [{:keys [evaluate-document]}
        (make-sci-evaluator slurp-resource)
        result (evaluate-document "complex-doc/master.prose")
        namespace-tags
        (filterv #(and (map? %) (= :ns (:tag %)))
                 (tree-seq map?
                           :content
                           {:tag :doc
                            :content (:document result)}))
        first-tag (first namespace-tags)]
    (is (= {:tag-count 3
            :same-parent-context? true}
           {:tag-count (count namespace-tags)
            :same-parent-context?
            (every? #(= first-tag %) (rest namespace-tags))}))))
