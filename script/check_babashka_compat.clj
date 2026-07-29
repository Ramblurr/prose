(ns script.check-babashka-compat
  (:require
    [clojure.test :refer [deftest is run-tests]]
    [fr.jeremyschoffen.prose.alpha.document.lib :as lib]
    [fr.jeremyschoffen.prose.alpha.document.sci.bindings :as bindings]
    [fr.jeremyschoffen.prose.alpha.eval.common :as eval-common]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.html.tags :as html-tags]
    [fr.jeremyschoffen.prose.alpha.out.latex.compiler :as latex]
    [fr.jeremyschoffen.prose.alpha.out.markdown.compiler :as markdown]
    [fr.jeremyschoffen.prose.alpha.out.markdown.tags :as markdown-tags]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]))


(deftest compatible-modules
  (is (= {:evaluation [3]
          :document-tag {:tag :strong
                         :content ["42"]
                         :type :tag}
          :namespace-bindings? true
          :html "<strong>42</strong>"
          :markdown "```text\n42\n```"
          :latex "\\strong{42}"}
         {:evaluation (eval-common/eval-forms '[(+ 1 2)])
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
            :parser-libraries-loaded?
            (boolean
             (some (fn [namespace]
                     (re-find #"^(instaparse|lambdaisland\.regal)"
                              (str (ns-name namespace))))
                   (all-ns)))}))))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error))
    (System/exit 1)))
