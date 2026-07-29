(ns fr.jeremyschoffen.prose.alpha.reader.core-test
  (:require
    #?(:clj [clojure.test :refer [deftest is are]]
       :cljs [cljs.test :refer-macros [deftest is are]])
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


(deftest reads-public-syntax-as-clojure-data
  (are [source expected] (= expected (c/read-from-string source))
    "plain text" '["plain text"]
    "◊\"◊literal\"" '["◊literal"]
    "◊|some.ns/value" '[some.ns/value]
    "◊(inc 1)" '[(inc 1)]
    "◊outer [1] {first} [2 3] {second ◊inner{x}}"
    '[(outer 1 "first" 2 3 "second " (inner "x"))]))

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
    "line one\nbefore ◊(inc 1"
    {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/syntax-error
     :start-index 16
     :end-index 23
     :start-line 2
     :start-column 8
     :end-line 2
     :end-column 15
     :text "◊(inc 1"}

    "line one\nbefore ◊(#unknown/tag 1) after"
    {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/syntax-error
     :start-index 16
     :end-index 33
     :start-line 2
     :start-column 8
     :end-line 2
     :end-column 25
     :text "◊(#unknown/tag 1)"}))
