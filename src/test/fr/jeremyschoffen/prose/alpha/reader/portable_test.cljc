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
