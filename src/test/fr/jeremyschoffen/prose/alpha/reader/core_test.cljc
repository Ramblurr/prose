(ns fr.jeremyschoffen.prose.alpha.reader.core-test
  (:require
    #?(:clj [clojure.test :refer [deftest testing is are]]
       :cljs [cljs.test :refer-macros [deftest testing is are]])
    [fr.jeremyschoffen.prose.alpha.reader.core :as c]))



(def simple-form '(+ 1 2 3))
(def simple-form-textp (str \◊ simple-form))


(deftest round-trips
  (is (= simple-form (first (c/read-from-string simple-form-textp)))))


(def example1
  "some text and ◊a-tag")

(deftest form->text
  (is (= "◊a-tag"
         (-> example1
             c/read-from-string
             second
             (c/form->text example1)))))

(def example
  "Some text. ◊div[{:class ◊str{c1 c2}}] { ◊def[x 1] ◊(def y 2) }")


(deftest complex-example
  (is (= (c/read-from-string example)
         '["Some text. " (div {:class (str "c1 c2")} " " (def x 1) " " (def y 2) " ")])))

(deftest reads-public-syntax-as-clojure-data
  (are [source expected] (= expected (c/read-from-string source))
    "plain text" '["plain text"]
    "◊\"◊literal\"" '["◊literal"]
    "◊|some.ns/value" '[some.ns/value]
    "◊(inc 1)" '[(inc 1)]
    "◊tag [1]\n  {body}" '[(tag 1 "body")]
    "◊outer [1] {first} [2 3] {second ◊inner{x}}"
    '[(outer 1 "first" 2 3 "second " (inner "x"))]
    "◊◊group[a b]{body}" '[([group] [a b] ["body"])]
    "◊(vector ◊outer[:class \"x\"]{before ◊inner{after}})"
    '[(vector (outer :class "x" "before " (inner "after")))]))

(deftest recovers-exact-source-text
  (let [source "before ◊outer[1]{body ◊inner{x}} and ◊(+ 1 2) after"
        document (c/read-from-string source)
        outer (second document)
        inner (nth outer 3)
        clojure-call (nth document 3)]
    (is (= {:document source
            :outer "◊outer[1]{body ◊inner{x}}"
            :inner "◊inner{x}"
            :clojure-call "◊(+ 1 2)"}
           {:document (c/form->text document source)
            :outer (c/form->text outer source)
            :inner (c/form->text inner source)
            :clojure-call (c/form->text clojure-call source)}))))

(def error-location-keys
  [:type
   :start-index
   :end-index
   :start-line
   :start-column
   :end-line
   :end-column
   :text])

(defn read-error-location [source]
  (try
    (c/read-from-string source)
    (catch #?@(:clj [Exception e] :cljs [js/Error e])
      (select-keys (ex-data e) error-location-keys))))

(deftest normalizes-failure-categories-and-locations
  (are [source expected] (= expected (read-error-location source))
    "line one\nbefore ◊"
    {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/grammar-error
     :end-index 17
     :end-line 2
     :end-column 9
     :text "before ◊"}

    "line one\nbefore ◊(inc 1"
    {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/grammar-error
     :end-index 23
     :end-line 2
     :end-column 15
     :text "before ◊(inc 1"}

    "line one\nbefore ◊(#unknown/tag 1) after"
    {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/clojure-reader-error
     :start-index 16
     :end-index 33
     :start-line 2
     :start-column 8
     :end-line 2
     :end-column 25
     :text "(#unknown/tag 1)"}))
