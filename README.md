
# Prose

Alternate syntax for Clojure, similar to what [Pollen](https://github.com/mbutterick/pollen) brings to [Racket](https://racket-lang.org/).

Try it now in your browser at https://ramblurr.github.io/prose

- Supports: Clojure, Clojurescript, Babashka
- Project Status: [Experimental](https://docs.outskirtslabs.com/open-source-vital-signs#experimental)

## Installation
```clojure
{io.github.ramblurr/prose {:git/sha "30ce9ccd22cefb08cbe3fe85ed6edb329ff55228"}}
```

Then, optionally (but recommended), install an editor plugin/package for it.
See [editors/README.md](editors/README.md).

## Usage
The main idea is to have programmable documents in Clojure. To do so, Prose
flips the relationship between plain text and code. In a Clojure file, text is
assumed to be code except in special cases like strings and comments.
In Prose, text is assumed to be just plain text except in special cases i.e.
Clojure code.

### Syntax
Prose provides a reader similar to what we can find in [Pollen](https://github.com/mbutterick/pollen). Text is
either plain text or a special construct. All special constructs begin with
the character `◊`(lozenge).

#### Clojure calls:
The text:
```text
We can call ◊(str "code") in text
```
reads as:
```clojure
["We can call " (str "code") " in text"]
```

#### Clojure symbols:
The text:
```text
We can use symbols ◊|some-symbol
```
reads as:
```clojure
["We can use symbols " some-symbol]
```

#### Tag function:
The text:
```text
There is a tag function syntax looking like:
◊div[{:class "grid"}]{ some content}
◊div{ some ◊em{content}}

or even:
◊str{text}
```
reads as:
```clojure
["There is a tag function syntax looking like:\n" (div {:class "grid"} " some content") "\n" (div " some " (em "content")) "\n\nor even:\n" (str "text")]
```

- Clojure code argument in brackets
- text argument in braces

#### Escaped / verbatim text:
The text:
```text
The ◊"◊" character.
```
reads as:
```clojure
["The " "◊" " character."]
```


### Documents as programs
To get programmable documents prose provides several apis that are meant to
work together. We have:
- a reader turning text into code as data
- an API help to evaluate that data using Clojure's eval capabilities
- an API to compile the result of evaluations into the final document

Let's see the whole process in action. We start by requiring the necessary
apis and setting up a little helper:
```clojure

(require '[clojure.java.io :as io])
(require '[clojure.pprint :as pp])
(require '[fr.jeremyschoffen.prose.alpha.reader.core :as reader])
(require '[fr.jeremyschoffen.prose.alpha.document.clojure :as doc])
(require '[fr.jeremyschoffen.prose.alpha.out.html.compiler :as html-compiler])

```

```clojure

(defn display [x]
  (with-out-str
    (pp/pprint x)))

```

This is the document we are using for our example:
```text
◊(require '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [div ul li]])

◊div{
  some text
  ◊ul {
    ◊li {1}
    ◊li {2}
  }
}
```

Let's read it:
```clojure

(def document
  (-> "prose/docs/readme/example-doc.html.prose"
    io/resource
    slurp
    reader/read-from-string))

(display document)

```
;=>
```clojure
[(require
  '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [div ul li]])
 "\n\n"
 (div
  "\n  some text\n  "
  (ul "\n    " (li "1") "\n    " (li "2") "\n  ")
  "\n")]

```

Evaluate it one top-level item at a time:
```clojure

(def evaluation
  (doc/evaluate-document
    "prose/docs/readme/example-doc.html.prose"))

(display evaluation)

```
;=>
```clojure
{:forms
 [(require
   '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [div ul li]])
  "\n\n"
  (div
   "\n  some text\n  "
   (ul "\n    " (li "1") "\n    " (li "2") "\n  ")
   "\n")],
 :document
 [nil
  "\n\n"
  {:tag :div,
   :content
   ["\n  some text\n  "
    {:tag :ul,
     :content
     ["\n    "
      {:tag :li, :content ["1"], :type :tag}
      "\n    "
      {:tag :li, :content ["2"], :type :tag}
      "\n  "],
     :type :tag}
    "\n"],
   :type :tag}]}

```

Compile it to html:
```clojure

(html-compiler/compile! (:document evaluation))

```
;=>
```clojure


<div>
  some text
  <ul>
    <li>1</li>
    <li>2</li>
  </ul>
</div>
```

There are some helpers to make this process easier:
```clojure

(require '[fr.jeremyschoffen.prose.alpha.document.clojure :as doc])

```
```clojure

(defn slurp-doc [path]
  (-> path
    io/resource
    slurp))

(def evaluate (doc/make-evaluator {:slurp-doc slurp-doc}))
(-> "prose/docs/readme/example-doc.html.prose"
  evaluate
  :document
  html-compiler/compile!)

```
;=>
```clojure


<div>
  some text
  <ul>
    <li>1</li>
    <li>2</li>
  </ul>
</div>
```

The namespaces `fr.jeremyschoffen.prose.alpha.document.*` provide more
functionality than just composing `slurp`, `read` and `eval` functions.
The `make-evaluator` functions there sets up the possibility
for documents to import other documents, passing input data to documents...

`reader/read-from-string` is pure: it returns ordinary Clojure data without
executing it. Document evaluation performs real effects. It first checks the
whole source for structural errors, then reads and evaluates one top-level item
at a time so that namespace changes and aliases affect later items.

A successful evaluation returns `:forms`, the intermediate forms, and
`:document`, their evaluated values. Pass only `:document` to an output compiler.
The evaluator accepts `{:initial-ns 'some.namespace}` as its third argument.
Without it, evaluation starts in a hidden temporary namespace. Read and
evaluation failures retain completed `:forms` and `:document` values in their
exception data; evaluation failures also identify the failing form.

## The ◊ (lozenge) character
One of the first question that came to mind when I discovered [Pollen](https://github.com/mbutterick/pollen) was:
why this `◊` character? I expect the same question will arise for this
project.

[Pollen](https://github.com/mbutterick/pollen) and Prose use `◊` for several reasons. Mainly this character
isn't used as a special character in programming languages. To stick to
Clojure, characters like `@`, `#` or even `&` have special meaning.
`◊` not being used either in clojure nor very much in plain text allows
us to have expressions such as:
```text
◊(defn template [v]
   ◊div { Some value: ◊|v})
```

In this example there is prose syntax used inside clojure code without
ambiguity. Using the `@` as [Scribble](https://docs.racket-lang.org/scribble/index.html) does would cause problems:
```text
@(defn template [v]
   @div { Some value: @|v})
```

In that case which `@` hold Prose's meaning and which are a `deref` reader
macro? Using `◊` gets us out of most of these problems. When we want to
use `◊` as text we can use the escaping/verbatim syntax `◊"◊"`.
Also this:
```text
◊(str "◊")
```
behaves as you'd expect, the ◊ insisde the clojure string isn't special.
That should be the extent of our troubles with this character.

For reference here is the answer in the case of pollen from
[its documentation](https://docs.racket-lang.org/pollen/pollen-command-syntax.html#%28part._the-lozenge%29).

### But how do I type the ◊ (lozenge) character?
Pollen's manual provides [a bunch of specific helpers](https://docs.racket-lang.org/pollen/pollen-command-syntax.html#%28part._.Lozenge_helpers%29) for typing the lozenge.

I simply suggest configuring your editor to type the lozenge when you type a
character such as `@`, and to insert a literal `@` when you type `@@`. This is
relatively straightforward in most editors.

All Prose editor plugins/packages offer some sort of lozenge insert functionality out of the box.

See [editors/README.md](editors/README.md).

## Clojure vs sci evaluation
Currently Prose provides 2 apis to evaluate code. The first one uses Clojure's
eval function. The second uses [Sci](https://github.com/borkdude/sci).

There are pros and cons to each approach.

### Clojure
Pros:
- An evaluation can use anything that is in the classpath making requiring
  namespaces easier.
- The api may generally be a bit easier to use.

Cons:
- An evaluation can use anything that is in the classpath which isn't secure.
- I believe clojure doesn't allow code ran outside of its main thread to
  create / destroy namespaces.
- Porting that functionality to Clojurescript requires going self hosted
  (eval needs to be there somehow).

### Sci
Pros:
- Runs in clojure, clojurescript, and babashka
- Bringing it's own reifed environment, [Sci](https://github.com/borkdude/sci) evaluations can easily happen in
  several threads.
- Allows us to sandbox what's accessible to the code / document being evaluated.

Cons:
- May be a bit of a perf hit.
- Managing the sci context makes for an api not as easy to use.

### Limitations
The pure reader still needs namespace aliases supplied in its reader options.
The staged document evaluators instead use namespace changes from each evaluated
top-level item while reading the next one. SCI can resolve only namespaces and
vars exposed by its configured context. Babashka users run programmable
documents through
`fr.jeremyschoffen.prose.alpha.document.sci/make-evaluator`; Babashka 1.12.218
is the tested host. Prose keeps its configured inner SCI context and restores
the caller's current SCI namespace after success, failure, and recursive
requirements. This lifecycle is synchronous and makes no concurrency
guarantee for one mutable SCI context.


## Mentions
This work is of course inspired and influenced by [Pollen](https://github.com/mbutterick/pollen) and [Scribble](https://docs.racket-lang.org/scribble/index.html).
The [enlive](https://github.com/cgrand/enlive) library and ClojureScript are also
a big source of inspiration where document compilation is concerned.

## License

Copyright © 2020 Jeremy Schoffen.  
Copyright © 2026 Casey Link.

Distributed under the Eclipse Public License v 2.0.
