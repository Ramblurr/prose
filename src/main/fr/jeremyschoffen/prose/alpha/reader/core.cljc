(ns fr.jeremyschoffen.prose.alpha.reader.core
  "Reads Prose documents into evaluator-neutral Clojure data.

  [[read-from-string]] returns ordinary forms for either the trusted JVM
  evaluator or the separate SCI evaluator. [[form->text]] maps a returned form
  back to its exact source text."
  (:require
    [fr.jeremyschoffen.prose.alpha.reader.clojurizer :as clojurizer]
    [fr.jeremyschoffen.prose.alpha.reader.core.error :as error]
    [fr.jeremyschoffen.prose.alpha.reader.portable :as portable]))


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
    (binding [clojurizer/*reader-options* *reader-options*]
      (portable/read-from-string text))
    (catch #?@(:clj [Exception e] :cljs [js/Error e])
      (error/handle-read-error e))))


(defn read-from-string
  "Reads `text` as Prose syntax and returns evaluator-neutral Clojure data.

  Options:

  | key               | description
  | ----------------- | -----------
  | `:reader-options` | Edamame options for embedded Clojure; replaces the safe defaults. |

  The defaults support standard Clojure forms and disable `:read-eval`."
  ([text]
   (read-from-string* text))
  ([text opts]
   (binding [*reader-options* (get opts :reader-options *reader-options*)]
     (read-from-string* text))))


(defn form->text
  "Returns the exact part of `original` that produced `form`."
  [form original]
  (portable/form->text form original))
