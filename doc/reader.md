


# Prose's reader

The goal of Prose's reader is to translate a document that mixes prose and
Clojure into ordinary Clojure data. Text remains text, while commands introduced
by the lozenge (`◊`) become symbols, lists, and collections. The result can be
inspected like any other Clojure value or consumed by tools that work with
ordinary Clojure forms.

The reader only reads. It never evaluates a form, loads a namespace, or performs
a document's side effects. Most library users need two functions from
`fr.jeremyschoffen.prose.alpha.reader.core`:

- `fr.jeremyschoffen.prose.alpha.reader.core/read-from-string` reads a complete source string.
- `fr.jeremyschoffen.prose.alpha.reader.core/form->text` maps a returned form back to its original source.


## Reading a document

Require the public reader namespace:

```clojure

(require '[fr.jeremyschoffen.prose.alpha.reader.core :as reader])

```

Consider an `example.prose` document containing:

```prose
◊(def a 3)

Some ◊em{example} text: ◊|a◊"."
```

Read it with:

```clojure

(def source (slurp "example.prose"))
(reader/read-from-string source)

```

The result is a vector of ordinary Clojure values:

```clojure
[(def a 3) "\n\nSome " (em "example") " text: " a "."]

```

Notice that reading has not defined `a` or called `em`. The `(def a 3)` form,
the `(em "example")` form, and the symbol `a` are data. The surrounding prose
is preserved as strings, including its whitespace.


## The command language

In a Prose document, text is copied exactly as written until a `◊` (lozenge)
starts a command. Parentheses, brackets, braces, and other punctuation need no
escaping. The character after `◊` determines which kind of command follows.


### Clojure forms and symbols

`◊(form)` reads one parenthesized Clojure form. Prose preserves nested
collections and Clojure strings, so an expression such as
`◊(vector [1 {:x (inc 1)}])` becomes the corresponding Clojure list.

`◊|name` reads a simple or namespace-qualified symbol without adding a
function call. This syntax has no closing delimiter, so Prose keeps reading for
as long as the characters can belong to a Clojure symbol. A period can be part
of a symbol: `◊|a.` would produce the symbol `a.`.

In the first example, we instead want the symbol `a` followed by a period of
prose. The following `◊"."` is a second command. Its quoted contents are
verbatim text, so it produces the string `"."`.

Prose commands may also appear inside a parenthesized Clojure form. The reader
temporarily separates those nested commands, asks Edamame to read the enclosing
Clojure, and then puts the nested forms back in place.


### Tags and tag functions

A tag is Prose's compact syntax for a Clojure call form. It begins with a
`◊` followed directly by a Clojure symbol. That symbol names the tag function,
and the reader places it first in a list:

```prose
◊greeting
```

If this is the complete source, the reader returns:

```clojure
[(greeting)]

```

The outer vector represents the document. The inner list is the call form
produced by the tag. At this point, `greeting` is only a symbol. Reading does not
resolve or call the tag function.

Arguments to the tag function follow its name. Square brackets contain Clojure
values, while curly braces contain prose:

```prose
◊heading[2]{Introduction}
```

This reads as:

```clojure
[(heading 2 "Introduction")]

```

If the document is later evaluated, Clojure resolves `heading` and evaluates
this call normally. The value returned by the tag function becomes part of the
evaluated document.

Tags can contain richer Clojure values and other tags. For example:

```prose
◊a[{:href "/docs"}]{Read the ◊em{manual}.}
```

This reads as:

```clojure
[(a {:href "/docs"} "Read the " (em "manual") ".")]

```

The map from the square argument becomes the first argument to `a`. The curly
argument contributes the text `"Read the "`, the nested `em` tag, and the final
`"."`.

Square and curly arguments may be repeated and mixed. The reader preserves
their order:

```prose
◊combine[1]{two}[3]{four}
```

This reads as:

```clojure
[(combine 1 "two" 3 "four")]

```

Tag names may be namespace-qualified. Whitespace may appear between a tag name
and its arguments. Delimited arguments balance nested parentheses, brackets, or
braces, and delimiters inside Clojure strings do not close an argument.

A tag function is an ordinary Clojure function and may return any Clojure value.
That value is the tag result. For example, `◊str{text}` reads as
`(str "text")`. Here `str` is the tag function. Evaluating the call produces the
tag result `"text"`.


### Verbatim text and grouped tags

The `◊"text"` form inserts verbatim text. A lozenge inside it is ordinary
text, and a backslash escapes the next character:

```prose
A ◊"literal ◊ symbol".
```

reads as:

```clojure
["A " "literal ◊ symbol" "."]

```

The less common double-lozenge form keeps the tag name, square arguments, and
curly arguments grouped instead of splicing them into an ordinary function
call:

```prose
◊◊group[a b]{body}
```

reads as:

```clojure
[([group] [a b] ["body"])]

```

Thus `◊◊group[a b]{body}` becomes `([group] [a b] ["body"])`, rather than
`(group a b "body")`.


## How the reader is constructed

Earlier versions of Prose generated their parser with Instaparse, which limited support to Clojure ans ClojureScript.

The current reader uses a small recursive-descent scanner written in portable
CLJC, which also works in Babashka. It needs no parser generator: after a
lozenge, one character is enough to choose between verbatim text, symbol
insertion, parenthesized Clojure, a tag, and a grouped tag. Each delimited
command also has an explicit closing character.

Reading still happens in two phases:

1. The scanner makes a left-to-right pass over the Prose structure. It separates
   plain text from commands, balances nested delimiters, protects Clojure
   strings, and records each command's position in the source.
2. The clojurizer turns those internal nodes into ordinary Clojure data. Edamame
   reads the embedded Clojure fragments; Prose combines the resulting forms
   with the surrounding text and nested commands.

The scanner nodes are an implementation detail. `fr.jeremyschoffen.prose.alpha.reader.core/read-from-string`
always returns evaluator-neutral Clojure data rather than a parser-specific
syntax tree.


## Namespaces and `::` keywords

Most documents need no namespace setup. It matters when embedded Clojure uses
auto-resolved keywords such as `::title` or `::ui/button`.

Those keywords do not contain their complete namespace. Clojure normally fills
it in from the current namespace and its aliases:

- `::title` uses the current namespace.
- `::ui/button` uses the namespace assigned to the `ui` alias.

The Prose reader does not guess that context. You can provide it when reading:

```clojure

(reader/read-from-string
  "◊(vector ::title ::ui/button)"
  {:initial-ns 'guide.introduction
   :reader-options
   {:auto-resolve {'ui 'guide.ui}}})

```
;=>
```clojure
[(vector :guide.introduction/title :guide.ui/button)]
```

Here `:initial-ns` tells the reader what "the current namespace" means, so
`::title` becomes `:guide.introduction/title`. The alias map tells it what `ui`
means, so `::ui/button` becomes `:guide.ui/button`.

These settings affect only how the source is read. The reader does not create or
enter `guide.introduction`, load `guide.ui`, or execute any document code.

`:reader-options` is the lower-level escape hatch for configuring Edamame, the
Clojure reader used inside Prose commands. Most callers will not need it except
to supply aliases. When provided, this map replaces Prose's default Edamame
options rather than being merged with them. Without it, Prose enables the usual
Clojure forms and disables read-eval. See
[Edamame's documentation](https://github.com/borkdude/edamame) for the available
options.

If both `:initial-ns` and `:reader-options` specify a current namespace,
`:initial-ns` wins. Alias mappings from `:reader-options` are still preserved.

`read-from-string` does not run an `ns`, `require`, `alias`, or `in-ns` form that
it finds in the document. It only returns the form. That form therefore cannot
change how the reader understands the source that follows it.

If a document expects an earlier namespace declaration or alias to affect later
forms, use a [document evaluator](evaluation.md). It reads and runs each
top-level form before moving on to the next one.


## Recovering source text

Every returned non-text form carries a source region in its metadata.
`fr.jeremyschoffen.prose.alpha.reader.core/form->text` uses that region to recover the exact part of the
original document that produced the form:

```clojure

(let [source "Before ◊em{hello} after"
      forms (reader/read-from-string source)
      form (second forms)]
  (reader/form->text form source))

```
;=>
```clojure
◊em{hello}
```

A source region records where a form begins and ends. The indexes count
characters from the beginning of the document, starting at zero.

`:start-index` points to the first character of the form. `:end-index` points
to the first character after the form. In other words, it is a half-open
interval, like clojure.core/subs.

For example, the command in `"Before ◊em{hello} after"` has this region:

```clojure
{:start-index 7
 :end-index 17
 :start-line 1
 :start-column 8
 :end-line 1
 :end-column 18}
```

The vector returned for the complete document also has a source region. Its
region covers the whole source string.

Plain text is already returned as a string, so `form->text` does not need to
look up a region for it. It simply returns the string unchanged.


## Reader errors

Malformed commands fail at the offending source location. For example, reading
this incomplete form:

```clojure
(reader/read-from-string "line one\nbefore ◊(inc 1")
```

prints a diagnostic to standard error and throws an `ExceptionInfo` with the
message:

```text
Prose reader error at line 2, column 15: expected ).
```

The exception data contains the original source, failed text, half-open indexes,
line and column positions, and the expected construct. Its `:phase` distinguishes
a structural scanning error, such as an unmatched delimiter, from an error while
Edamame reads embedded Clojure.


## Reading versus evaluating

Use `fr.jeremyschoffen.prose.alpha.reader.core/read-from-string` when you want to inspect or transform a
whole document without running it. Do not call `eval` directly on the returned
vector when the document is a program. Prose's document evaluators scan the
complete structure, then read and evaluate one top-level item at a time so that
earlier namespace changes can affect later reading.

`fr.jeremyschoffen.prose.alpha.reader.core/reduce-top-level` is the lower-level seam used to build such an
evaluator. Most applications should use
`fr.jeremyschoffen.prose.alpha.document.clojure/make-evaluator` for trusted JVM
code or `fr.jeremyschoffen.prose.alpha.document.sci/make-evaluator` for an
isolated, portable environment. When Prose runs under Babashka, use the SCI
document evaluator for programmable documents.

> Now that we know how Prose reads a document, let's see how it runs one. Continue to [Evaluation model](evaluation.md).
