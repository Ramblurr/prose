(ns fr.jeremyschoffen.prose.alpha.out.html-test
  (:require
    #?(:clj [clojure.test :refer [deftest is]]
       :cljs [cljs.test :refer-macros [deftest is]])
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as cplr]
    [fr.jeremyschoffen.prose.alpha.out.html.tags :as tags]))


(def ex [(tags/html5-dtd)
         (tags/html
           (tags/head)
           (tags/body
             {:class "a b c"}
             "Some text"
             (tags/ul
               (for [i (range 3)]
                 (tags/li i)))))])


(deftest html-tag-and-compiler-portability
  (is (= "<!DOCTYPE html>\n<html><head></head><body class=\"a b c\">Some text<ul><li>0</li><li>1</li><li>2</li></ul></body></html>"
         (cplr/compile! ex))))

(deftest escapes-repeated-html-metacharacters
  (is (= "<p title=\"&quot;&quot;&amp;&amp;&lt;&lt;&gt;&gt;\">&lt;&lt;&amp;&amp;&gt;&gt;</p>"
         (cplr/compile! (tags/p {:title "\"\"&&<<>>"} "<<&&>>")))))

