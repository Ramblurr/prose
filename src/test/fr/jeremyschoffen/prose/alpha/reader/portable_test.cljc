(ns fr.jeremyschoffen.prose.alpha.reader.portable-test
  (:require
    #?(:clj [clojure.test :refer [deftest is are]]
       :cljs [cljs.test :refer-macros [deftest is are]])
    [fr.jeremyschoffen.prose.alpha.reader.portable :as portable]))


(deftest reads-ordinary-text-without-normalizing-whitespace
  (are [source] (= [source] (portable/read-from-string source))
    "plain text with () [] {} and punctuation"
    "Unicode: Καλημέρα κόσμε 👋"
    "first line\n  indented second line\n")
  (is (= [] (portable/read-from-string ""))))


(deftest preserves-verbatim-content-and-escapes
  (are [source expected] (= expected (portable/read-from-string source))
    "◊\"literal ◊ text\"" ["literal ◊ text"]
    "before ◊\"literal◊\" after" ["before " "literal◊" " after"]
    "◊\"a\\\"b\"" ["a" "\"" "b"]
    "◊\"a\\\\b\"" ["a" "\\" "b"]
    "◊\"\"" [])
  (is (= ["a" "◊" "b"]
         (portable/read-from-string (str "◊\"a" \\ \◊ "b\"")))))


(deftest reads-symbols-with-current-token-boundaries
  (are [source expected] (= expected (portable/read-from-string source))
    "◊|simple" '[simple]
    "before ◊|some.ns/value after" '["before " some.ns/value " after"]
    "◊|simple [after]" '[simple " [after]"]
    "◊|some.ns/value\nnext" '[some.ns/value "\nnext"]
    "◊|foo/bar/baz" '[foo/bar "/baz"]
    "◊|foo/1bar" '[foo "/1bar"]
    "◊|simple\u00a0after" '[simple "\u00a0after"]))


(deftest reads-parenthesized-clojure-forms
  (are [source expected] (= expected (portable/read-from-string source))
    "◊(+ 1 (* 2 3))" '[(+ 1 (* 2 3))]
    "before ◊(vector [1 {:x (inc 1)}]) after"
    '["before " (vector [1 {:x (inc 1)}]) " after"]))


(deftest protects-clojure-strings-while-balancing-parentheses
  (are [source expected] (= expected (portable/read-from-string source))
    "◊(str \"closing ) and ◊|plain\" \"escaped: \\\" and \\\\ slash\")"
    '[(str "closing ) and ◊|plain" "escaped: \" and \\ slash")]
    "◊(str \"([{}])\")" '[(str "([{}])")]))


(deftest restores-supported-prose-commands-inside-clojure
  (is (= '[(vector some.ns/value (inc 1) 42)]
         (portable/read-from-string
           "◊(vector ◊|some.ns/value ◊(inc 1) ◊\"42\")"))))


(deftest honors-reader-options-and-safe-defaults
  (is (= '[((clojure.core/deref state))]
         (portable/read-from-string "◊(@state)")))
  (is (= '[(:example.ns/value)]
         (portable/read-from-string
           "◊(::alias/value)"
           {:reader-options {:auto-resolve {'alias 'example.ns}}})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (portable/read-from-string "◊(#=(+ 1 2))"))))
