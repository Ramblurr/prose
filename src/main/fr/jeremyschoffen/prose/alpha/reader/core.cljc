(ns ^{:author "Jeremy Schoffen"
      :doc "
This namespaces provides a reader that combines our grammar and clojure's reader to turn a string of prose text into
data clojure can then evaluate.

The reader starts by parsing the text using our grammar. This gives a first data representation from which
is computed data that clojure can evaluate.

The different syntactic elements are processed as follows:
- text -> string
- clojure call -> itself
- symbol -> itself
- tag -> clojure fn call
- verbatim block -> string containing the verbatim block's content.
"}
  fr.jeremyschoffen.prose.alpha.reader.core
  (:require
    [instaparse.core :as insta]

    [fr.jeremyschoffen.prose.alpha.reader.clojurizer :as clojurizer]
    [fr.jeremyschoffen.prose.alpha.reader.grammar :as g]
    [fr.jeremyschoffen.prose.alpha.reader.core.error :as error]))


;;----------------------------------------------------------------------------------------------------------------------
;; Parsing and reading
;;----------------------------------------------------------------------------------------------------------------------

#_{:clj-kondo/ignore [:unresolved-var]}
(defn parse
  "Wrapper around the parser from [[textp.reader.grammar]] adding error handling."
  [text]
  (let [parsed (g/parser text)]
    (when (insta/failure? parsed)
      (throw (ex-info "Parser failure."
                      {:type ::error/grammar-error
                       :failure (insta/get-failure parsed)})))
    (insta/add-line-and-column-info-to-metadata text parsed)))


(def ^:dynamic *parse-region*
  "Stores the parse region while clojurizing a parse node."
  {})

(def ^:dynamic *reader-options* clojurizer/*reader-options*)

(defn read-string*
  "Wraps Edamame's reader for use in the Prose reader."
  [s]
  (binding [clojurizer/*parse-region* *parse-region*
            clojurizer/*reader-options* *reader-options*]
    (clojurizer/read-string* s)))

(defn extract-tags
  "Replaces tags with unique symbols and maps those symbols to the replaced tag data."
  [content]
  (clojurizer/extract-tags content))

(defn inject-clojurized-tags
  "Replaces placeholder symbols with clojurized tag data."
  [form env]
  (binding [clojurizer/*parse-region* *parse-region*
            clojurizer/*reader-options* *reader-options*]
    (clojurizer/inject-clojurized-tags form env)))

(defn clojurize-mixed
  "Reads mixed Clojure text and nested Prose nodes as Clojure data."
  [content]
  (binding [clojurizer/*parse-region* *parse-region*
            clojurizer/*reader-options* *reader-options*]
    (clojurizer/clojurize-mixed content)))

(defn add-type [x t]
  (clojurizer/add-type x t))
(def clojurize* clojurizer/clojurize*)

(defn clojurize
  "Function that turns a prose parse tree to data that clojure can eval.
  clojure form that are clojurized have parse info in their metadata."
  [form]
  (binding [clojurizer/*parse-region* *parse-region*
            clojurizer/*reader-options* *reader-options*]
    (clojurizer/clojurize form)))


(defn read-from-string* [text]
  (try
    (let [parsed (parse text)]
      (clojurize parsed))
    (catch #?@(:clj [Exception e] :cljs [js/Error e])
      (error/handle-read-error e))))


(defn read-from-string
  "
  The entry point of the reader.

  Args:
  - `text`: string to read
  - `opts`: a map specifying options

  Options:
  - `:reader-options`: The options to pass the clojure reader, it's the map that will be passed to
    [[edamame.core/parse-string]]. By default every basic option is allowed except `:read-eval`.
  "
  ([text]
   (read-from-string* text))
  ([text opts]
   (binding [*reader-options* (get opts :reader-options *reader-options*)]
     (read-from-string* text))))


(defn form->text
  "Given a form and the original text, finds the part of the text that read as this form."
  [form original]
  (if (string? form)
    form
    (let [{:keys [start-index end-index]} (-> form meta ::parse-region)]
      (subs original start-index end-index))))


(comment
  (def ex1
    "Hello my name is ◊em{Jeremy}{Schoffen}.
     We can embed code ◊(+ 1 2 3).
     We can even embed tags in code:
     ◊(call ◊text{◊em{Me!}})

     Tags ins tags args:
     ◊toto[:arg1 ◊em{toto} :arg2 2 :arg3 \"arg 3\"].

     The craziest, we can embed ad nauseam:

     ◊(defn template [x]
        ◊div
        {
          the value x: ◊|x
          the value x++: ◊(inc x)
        })")
  (g/parser ex1)
  (read-from-string ex1)

  (read-from-string "◊div[:a \"stuff]\" :b 1]")
  (read-from-string "some text ◊(str \"aaa\"\")")

  (read-from-string "◊div{wanted to use the ◊\"}\" char}")
  (read-from-string "◊◊div{wanted to use the ◊\"}\" char}{in} [there]")
  (->> (read-from-string "◊◊div{wanted to use the ◊\"}\" char}{in} [there]")
       first
       (map meta))

  (read-from-string "◊[ 1 2 3 a]")
  (read-from-string "◊str◊{some str}")
  (def ex2
    "◊code{
      (defn toto [{:keys [a b c]}]
        [a b c])
     }")
  (g/parser ex2)
  (-> ex2
      read-from-string
      pr-str
      println))

