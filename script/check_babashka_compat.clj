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
    [fr.jeremyschoffen.prose.alpha.reader.portable :as portable]))


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


(deftest portable-reader-path
  (is (= {:forms '["Unicode α\nbefore "
                    (vector some.ns/value (inc 1) 42)
                    " and "
                    "literal ◊ text"]
          :reader-options '[(:example.ns/value)]
          :parser-libraries-loaded? false}
         {:forms (portable/read-from-string
                  "Unicode α\nbefore ◊(vector ◊|some.ns/value ◊(inc 1) ◊\"42\") and ◊\"literal ◊ text\"")
          :reader-options (portable/read-from-string
                           "◊(::alias/value)"
                           {:reader-options
                            {:auto-resolve {'alias 'example.ns}}})
          :parser-libraries-loaded?
          (boolean
           (some (fn [namespace]
                   (re-find #"^(instaparse|lambdaisland\.regal)"
                            (str (ns-name namespace))))
                 (all-ns)))})))


(let [{:keys [fail error]} (run-tests)]
  (when (pos? (+ fail error))
    (System/exit 1)))
