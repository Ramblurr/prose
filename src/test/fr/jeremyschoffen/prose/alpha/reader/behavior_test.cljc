(ns fr.jeremyschoffen.prose.alpha.reader.behavior-test
  (:require
    #?(:clj [clojure.test :refer [deftest is are]]
       :cljs [cljs.test :refer-macros [deftest is are]])
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]))


(deftest reads-ordinary-text-without-normalizing-whitespace
  (are [source] (= [source] (reader/read-from-string source))
    "plain text with () [] {} and punctuation"
    "Unicode: Καλημέρα κόσμε 👋"
    "first line\n  indented second line\n")
  (is (= [] (reader/read-from-string ""))))


(deftest preserves-verbatim-content-and-escapes
  (are [source expected] (= expected (reader/read-from-string source))
    "◊\"literal ◊ text\"" ["literal ◊ text"]
    "before ◊\"literal◊\" after" ["before " "literal◊" " after"]
    "◊\"a\\\"b\"" ["a" "\"" "b"]
    "◊\"a\\\\b\"" ["a" "\\" "b"]
    "◊\"\"" [])
  (is (= ["a" "◊" "b"]
         (reader/read-from-string (str "◊\"a" \\ \◊ "b\"")))))


(deftest reads-symbols-with-current-token-boundaries
  (are [source expected] (= expected (reader/read-from-string source))
    "◊|simple" '[simple]
    "before ◊|some.ns/value after" '["before " some.ns/value " after"]
    "◊|simple [after]" '[simple " [after]"]
    "◊|some.ns/value\nnext" '[some.ns/value "\nnext"]
    "◊|foo/bar/baz" '[foo/bar "/baz"]
    "◊|foo/1bar" '[foo "/1bar"]
    "◊|simple\u00a0after" '[simple "\u00a0after"]))


(deftest reads-parenthesized-clojure-forms
  (are [source expected] (= expected (reader/read-from-string source))
    "◊(+ 1 (* 2 3))" '[(+ 1 (* 2 3))]
    "before ◊(vector [1 {:x (inc 1)}]) after"
    '["before " (vector [1 {:x (inc 1)}]) " after"]))


(deftest protects-clojure-strings-while-balancing-parentheses
  (are [source expected] (= expected (reader/read-from-string source))
    "◊(str \"closing ) and ◊|plain\" \"escaped: \\\" and \\\\ slash\")"
    '[(str "closing ) and ◊|plain" "escaped: \" and \\ slash")]
    "◊(str \"([{}])\")" '[(str "([{}])")]))


(deftest restores-supported-prose-commands-inside-clojure
  (is (= '[(vector some.ns/value (inc 1) 42)]
         (reader/read-from-string
           "◊(vector ◊|some.ns/value ◊(inc 1) ◊\"42\")"))))


(deftest honors-reader-options-and-safe-defaults
  (is (= '[((clojure.core/deref state))]
         (reader/read-from-string "◊(@state)")))
  (is (= '[(:example.ns/value)]
         (reader/read-from-string
           "◊(::alias/value)"
           {:reader-options {:auto-resolve {'alias 'example.ns}}})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (reader/read-from-string "◊(#=(+ 1 2))"))))

(deftest applies-initial-namespace-throughout-document
  (is (= '[(vector :assigned.document/parenthesized
                   #:assigned.document{:mapped true})
           (outer :assigned.document/argument
                  #:alias.target{:mapped true}
                  (inner :assigned.document/nested))]
         (reader/read-from-string
           (str "◊(vector ::parenthesized #::{:mapped true})"
                "◊outer[::argument #::alias{:mapped true}]{◊inner[::nested]}")
           {:initial-ns 'assigned.document
            :reader-options {:auto-resolve {:current 'ignored.document
                                            'alias 'alias.target}}}))))

(deftest pure-reading-does-not-evaluate-forms
  (let [side-effect (atom 0)]
    (is (= '[(swap! side-effect inc)]
           (reader/read-from-string
             "◊(swap! side-effect inc)"
             {:initial-ns 'assigned.document})))
    (is (zero? @side-effect))))

(deftest reads-top-level-items-under-changing-context
  (let [source "◊(identity ::one)◊outer[::a/two]{◊inner[::nested]}"
        context (atom {:current 'first.document})
        {:keys [result source-region]}
        (reader/reduce-top-level
          source
          {:reader-context #(deref context)}
          (fn [forms form]
            (reset! context {:current 'second.document
                             :aliases {'a 'alias.target}})
            (conj forms form))
          [])]
    (is (= {:forms '[(identity :first.document/one)
                     (outer :alias.target/two
                            (inner :second.document/nested))]
            :source-text ["◊(identity ::one)"
                          "◊outer[::a/two]{◊inner[::nested]}"]
            :source-region {:start-index 0
                            :end-index 50
                            :start-line 1
                            :start-column 1
                            :end-line 1
                            :end-column 51}}
           {:forms result
            :source-text (mapv #(reader/form->text % source) result)
            :source-region source-region}))))

(deftest scans-complete-structure-before-reducing
  (let [reduced (atom [])]
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (reader/reduce-top-level
                   "◊(identity :first)◊outer{"
                   {}
                   (fn [forms form]
                     (swap! reduced conj form)
                     forms)
                   [])))
    (is (= [] @reduced))))


(deftest reads-tags-and-delimited-arguments
  (are [source expected] (= expected (reader/read-from-string source))
    "◊tag" '[(tag)]
    "◊some.ns/tag" '[(some.ns/tag)]
    "◊foo/bar/baz" '[(foo/bar) "/baz"]
    "◊foo/1bar" '[(foo) "/1bar"]
    "◊tag[1 2]" '[(tag 1 2)]
    "◊tag{body}" '[(tag "body")]
    "◊tag[{:x [1 2]} \"closing ]\"]" '[(tag {:x [1 2]} "closing ]")]
    "◊tag{before {nested} after}" '[(tag "before " "{" "nested" "}" " after")]))


(deftest preserves-tag-argument-order-and-whitespace
  (are [source expected] (= expected (reader/read-from-string source))
    "◊tag [1]\n  {body}" '[(tag 1 "body")]
    "◊tag[1]{two}[3]{four}" '[(tag 1 "two" 3 "four")]
    "◊tag\u00a0[1]" '[(tag 1)]
    "◊tag   trailing" '[(tag) "   trailing"]))


(deftest reads-recursively-nested-tags
  (are [source expected] (= expected (reader/read-from-string source))
    "◊outer{before ◊inner{x} after}"
    '[(outer "before " (inner "x") " after")]
    "◊outer[:value ◊inner{x}]"
    '[(outer :value (inner "x"))]
    "◊(vector ◊outer[:class \"x\"]{before ◊inner{after}})"
    '[(vector (outer :class "x" "before " (inner "after")))]))


(deftest preserves-grouped-tag-shape
  (are [source expected] (= expected (reader/read-from-string source))
    "◊◊group[a b]{body}" '[([group] [a b] ["body"])]
    "◊◊some.ns/group [a] {body}" '[([some.ns/group] [a] ["body"])]))


(deftest reads-representative-document-with-tags
  (is (= '["Some text. "
           (div {:class (str "c1 c2")} " " (def x 1) " " (def y 2) " ")]
         (reader/read-from-string
           "Some text. ◊div[{:class ◊str{c1 c2}}] { ◊def[x 1] ◊(def y 2) }"))))

(defn form-region [form]
  (-> form
      meta
      :fr.jeremyschoffen.prose.alpha.reader.core/parse-region))

(defn read-error [source]
  (try
    (reader/read-from-string source)
    (catch #?@(:clj [Exception e] :cljs [js/Error e])
      e)))

(defn error-summary [source]
  (-> (read-error source)
      ex-data
      (select-keys [:text :index :line :column :expected])))

(deftest attaches-half-open-source-regions-and-positions
  (let [source "first\n◊outer[1]{body\n  ◊inner{x}} and ◊(+ 1 2)"
        document (reader/read-from-string source)
        outer (second document)
        inner (nth outer 3)
        clojure-call (nth document 3)]
    (is (= {:document {:start-index 0
                       :end-index 46
                       :start-line 1
                       :start-column 1
                       :end-line 3
                       :end-column 26}
            :outer {:start-index 6
                    :end-index 33
                    :start-line 2
                    :start-column 1
                    :end-line 3
                    :end-column 13}
            :inner {:start-index 23
                    :end-index 32
                    :start-line 3
                    :start-column 3
                    :end-line 3
                    :end-column 12}
            :clojure-call {:start-index 38
                           :end-index 46
                           :start-line 3
                           :start-column 18
                           :end-line 3
                           :end-column 26}}
           {:document (form-region document)
            :outer (form-region outer)
            :inner (form-region inner)
            :clojure-call (form-region clojure-call)}))))

(deftest attaches-regions-to-each-non-text-node-category
  (let [document (reader/read-from-string
                   "pre\n◊|value ◊◊group[1]{body ◊inner{x}}")
        symbol (second document)
        command (nth document 3)
        name-form (first command)
        square-argument (second command)
        text-argument (nth command 2)
        inner (second text-argument)
        indexes #(select-keys (form-region %)
                              [:start-index :end-index])]
    (is (= {:symbol {:start-index 4 :end-index 11}
            :grouped-tag {:start-index 12 :end-index 38}
            :name {:start-index 14 :end-index 19}
            :square-argument {:start-index 19 :end-index 22}
            :text-argument {:start-index 22 :end-index 38}
            :nested-command {:start-index 28 :end-index 37}}
           {:symbol (indexes symbol)
            :grouped-tag (indexes command)
            :name (indexes name-form)
            :square-argument (indexes square-argument)
            :text-argument (indexes text-argument)
            :nested-command (indexes inner)}))))

(deftest recovers-exact-source-at-every-nesting-level
  (let [source "pre\n◊◊group[1]{body ◊inner{x}}"
        document (reader/read-from-string source)
        command (second document)
        name-form (first command)
        square-argument (second command)
        text-argument (nth command 2)
        inner (second text-argument)]
    (is (= {:document source
            :command "◊◊group[1]{body ◊inner{x}}"
            :name "group"
            :square-argument "[1]"
            :text-argument "{body ◊inner{x}}"
            :inner "◊inner{x}"}
           {:document (reader/form->text document source)
            :command (reader/form->text command source)
            :name (reader/form->text name-form source)
            :square-argument (reader/form->text square-argument source)
            :text-argument (reader/form->text text-argument source)
            :inner (reader/form->text inner source)}))))

(deftest owns-normalized-error-data
  (let [source "line one\nbefore ◊"
        error (read-error source)
        data (ex-data error)]
    (is (= {:type :fr.jeremyschoffen.prose.alpha.reader.core.error/syntax-error
            :phase :structural-scan
            :source source
            :text "◊"
            :start-index 16
            :end-index 17
            :start-line 2
            :start-column 8
            :end-line 2
            :end-column 9
            :index 17
            :line 2
            :column 9
            :expected "a command"}
           data))
    (is (= "Prose reader error at line 2, column 9: expected a command."
           (ex-message error)))
    (is (not (re-find #"instaparse" (pr-str data))))))

(deftest normalizes-malformed-input-categories
  (are [source expected] (= expected (error-summary source))
    "line one\nbefore ◊"
    {:text "◊"
     :index 17
     :line 2
     :column 9
     :expected "a command"}

    "line one\nbefore ◊[1]"
    {:text "◊["
     :index 17
     :line 2
     :column 9
     :expected "a tag name"}

    "line one\nbefore ◊|["
    {:text "◊|["
     :index 18
     :line 2
     :column 10
     :expected "a symbol"}

    "line one\nbefore ◊\"abc"
    {:text "◊\"abc"
     :index 21
     :line 2
     :column 13
     :expected "\""}

    "line one\nbefore ◊(str \"abc)"
    {:text "◊(str \"abc)"
     :index 27
     :line 2
     :column 19
     :expected "\""}

    "line one\nbefore ◊(inc 1"
    {:text "◊(inc 1"
     :index 23
     :line 2
     :column 15
     :expected ")"}

    "line one\nbefore ◊tag[1"
    {:text "[1"
     :index 22
     :line 2
     :column 14
     :expected "]"}

    "line one\nbefore ◊tag{body"
    {:text "{body"
     :index 25
     :line 2
     :column 17
     :expected "}"}

    "line one\nbefore ◊(#unknown/tag 1)"
    {:text "◊(#unknown/tag 1)"
     :index 16
     :line 2
     :column 8
     :expected "valid Clojure syntax"}))

(deftest reads-a-large-mixed-document
  (let [repetitions 1000
        chunk "text\n◊outer[1]{body ◊inner{x}} ◊(+ 1 2)\n"
        source (apply str (repeat repetitions chunk))
        forms (reader/read-from-string source)]
    (is (= {:form-count (inc (* 4 repetitions))
            :last-command "◊(+ 1 2)"
            :document-length (count source)}
           {:form-count (count forms)
            :last-command (reader/form->text (nth forms (- (count forms) 2))
                                               source)
            :document-length (-> forms form-region :end-index)}))))
