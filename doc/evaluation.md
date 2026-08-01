


# Evaluation model

Prose provides tools in the `fr.jeremyschoffen.prose.alpha.eval` namespace to
evaluate Clojure code. There are several things to take into account, just
using `eval` on 'code as data' isn't sufficient.


## Considerations
At the moment, the main use case of prose is the creation of programmable
documents by embedding Clojure inside them. In other words we want documents as
Clojure programs, because ["the book is code"][big-picture].

[big-picture]: https://docs.racket-lang.org/pollen/big-picture.html

The basic idea we cover here works in three phases:
- **read**: turn Prose text into 'Clojure code as data' using our reader
- **eval**: use Clojure's evaluation mechanism to evaluate this 'code as data'.
  The result of this evaluation is treated as data representing the final
  document (we tend to use enlive style trees).
- and, *cheeky grin*, **print**: compile the tree to a compilation target:
  HTML, LaTeX, or Markdown (like this document you are reading was created).

The `eval` part of this algorithm presents challenges that we explore here.

### Naive evaluation and `require`
Directly using the `eval` function on the data returned by our reader has
issues.

For instance the document:
```clojure
◊(require '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [div ul li]])

◊div{
  some text
  ◊ul {
    ◊li {1}
    ◊li {2}
  }
}
```

is read as:
```clojure

  [(require
  '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [div ul li]])
 "\n\n"
 (div
  "\n  some text\n  "
  (ul "\n    " (li "1") "\n    " (li "2") "\n  ")
  "\n")]


```

Using eval on this vector is problematic:
```clojure

  (try
    (-> "prose/docs/readme/example-doc.html.prose"
     lib/slurp-doc
     reader/read-from-string
     eval)
    (catch Exception e
      (-> e .getCause .getMessage)))

```
;=>
```clojure
Unable to resolve symbol: div in this context
```

This is a classic problem, requiring a namespace and using it in the same
evaluation is a no go in Clojure. To solve this, we can evaluate each element
in sequence and return the sequence of evaluations. It doesn't solve all
possible cases but it seems sufficient for most uses.


### Evaluation and namespaces interactions
Since we use eval, a lot of things can happen.

When it comes to Clojure namespaces in particular:
- Every var definition in made in the document will be added to the namespace
  from which the document is evaluated. One problem that can arise is that we
  can accidentally redefine vars that way.
- When evaluating several documents, all definitions made in documents could
  stack up in the same namespace and again interfere with each other.
- If for some reason the document uses code that changes the current namespace,
  it changes the current namespace for the code evaluating it.

To mitigate these types of issues our tools allow for the evaluation of
documents in ephemeral namespaces. The idea here is to create an new namespace
with a random name for each document evaluation. We can then switch to it, evaluate
the document, switch back and delete it. Unless code inside the document uses
something like `in-ns` or the `ns` macro, no definition made inside the document
should pollute the original namespace.

The use of Sci can is also a solution to these kind of problems since it allows
the creation of isolated evaluation environments.

### Multi-threading
We may want to evaluate several documents in parallel. However it seems like the
Clojure namespace machinery isn't meant to be used from several threads.

See:
```clojure

  (try
    (deref (future (eval-common/eval-forms-in-temp-ns
                    '[(+ 1 1)(+ 1 2)])))
    (catch Exception e
      (-> e ex-cause ex-cause ex-message)))

```
;=>
```clojure
Can't set!: *ns* from non-binding thread
```

Since we want to use ephemeral namespaces this is a problem for a pure Clojure
evaluation scheme. Sci is an interesting solution to get around this limitation.


## Reading and evaluating documents

The earlier example reads the entire document before it evaluates anything.
That is not sufficient for a document program: a form such as `require`, `ns`,
`in-ns`, or `alias` may change how later source must be read.

Prose therefore processes a document in source order. It first checks the
complete source for structural errors. It then reads and evaluates one
top-level item before reading the next. Namespace changes made by one item are
available when Prose reads the following item.

This applies at top-level boundaries. If code changes the namespace inside a
larger form, Prose has already read the rest of that form. The change takes
effect when it reads the next top-level item.

### Creating an evaluator

Document evaluation is inherently stateful. The current namespace, aliases,
and definitions can change as each item runs. They must remain available while
Prose processes the rest of the document and any documents it requires.

`make-evaluator` creates a function that manages this evaluation state and
restores the caller's state afterward. This is why we create and retain an
evaluator rather than composing `read` and `eval` for each call:

```clojure

(require '[fr.jeremyschoffen.prose.alpha.document.clojure :as document]
         '[fr.jeremyschoffen.prose.alpha.out.html.compiler :as html])

(def evaluate-document (document/make-evaluator))

```

The evaluator accepts a document path, optional input data, and optional
evaluation settings:

```clojure

(def result
  (evaluate-document path input {:initial-ns 'my.document}))

```

`:initial-ns` selects the namespace in which Prose begins reading and
evaluating the document. If you omit it, Prose creates a temporary namespace.
After evaluation, it restores the caller's namespace and removes the temporary
one.

### Results

A successful evaluation returns a map with two entries:

- `:forms` contains the top-level forms as Prose read them.
- `:document` contains the corresponding evaluated values.

Pass `:document` to an output compiler:

```clojure

(html/compile! (:document result))

```

This is real evaluation rather than a pure read. Document code can define vars,
perform I/O, and produce any other effects allowed by the evaluator.

### Errors

When evaluation fails, the `:phase` value in the exception data tells you what
Prose was doing:

- `:structural-scan` means the source has an unfinished command, an unmatched
  delimiter, or another structural problem. Prose checks for these problems
  before running any document code.
- `:read` means Prose could not read the next top-level item as Clojure data.
  Items before it may already have run.
- `:evaluation` means Prose read the item successfully, but evaluating it threw
  an exception.

Read and evaluation errors include the `:forms` and `:document` values
accumulated before the failure. Evaluation errors also identify the failing
form and its location in the source.

### Including other documents

A document can evaluate and include another document with `require-doc`. For
example, `report.md.prose` could contain:

```prose
◊(require '[fr.jeremyschoffen.prose.alpha.document.lib :as doc])

# Report

◊doc/require-doc{answer.md.prose}
```

And `answer.md.prose` could contain:

```prose
The answer is ◊(+ 20 22).
```

Prose evaluates `answer.md.prose` and inserts its document values into the
report. The resulting document contains `The answer is 42.`

The child begins with the parent's current namespace and aliases. If the child
changes its namespace, Prose restores the parent's namespace before continuing
with the next item in the parent.

`insert-doc` behaves differently: it reads the child and inserts its forms
without evaluating them. In the example above, replacing `require-doc` with
`insert-doc` would insert the `(+ 20 22)` form rather than its value.

### Choosing an evaluator

The example above uses the JVM Clojure evaluator. It is intended for trusted
documents and can access the runtime classpath.

For isolated or portable evaluation, use
`fr.jeremyschoffen.prose.alpha.document.sci/make-evaluator`. It follows the
same document API but exposes only the namespaces and vars configured in its
SCI context. When Prose itself runs under Babashka, use this SCI evaluator for
programmable documents: Babashka hosts Prose, while the configured SCI context
evaluates the document code.


## The lower-level evaluation toolkit
The `fr.jeremyschoffen.prose.alpha.eval` namespaces evaluate sequences of forms
that have already been read. They remain useful below the document evaluation API.
This evaluation model is inspired by the ring spec and lives in the
`fr.jeremyschoffen.prose.alpha.eval.common` namespace.

### Basics
As the ring model uses a map to represent a web request, we use a map to represent
an evaluation. Such maps are constructed with the
`fr.jeremyschoffen.prose.alpha.eval.common/make-evaluation-ctxt` function.
```clojure
(defn make-evaluation-ctxt
  "Make an evaluation context.

  This context is a map of 2 keys:
  - `:forms`: a sequence of forms to evaluate
  - `:eval-form`: a function that evaluates one form"
  [eval-form forms]
  {:forms forms
   :eval-form (wrap-eval-form-exception eval-form)})

```

The evaluation proper is realized by the `fr.jeremyschoffen.prose.alpha.eval.common/evaluate-ctxt`:
```clojure
(defn evaluate-ctxt
  "Function evaluating a context (produced by [[make-evaluation-ctxt]]).
  Returns the context with one of two keys associated:
  - `:result`: in the case of a successful evaluation the sequence of evaluations is returned here
  - `:error`: in the case of an error, the exception is returned here."
  [{:keys [forms eval-form]
    :as ctxt}]
  (let [[ret res] (try
                    [:result (eval-forms* eval-form forms)]
                    (catch #?@(:clj [Exception e] :cljs [js/Error e])
                      [:error e]))]
    (assoc ctxt ret res)))

```

Note that forms are evaluated in sequence using `fr.jeremyschoffen.prose.alpha.eval.common/eval-forms*`:
```clojure
(defn eval-forms*
  "Evaluate a sequences of forms `forms` in sequence with `eval-form`"
  [eval-form forms]
  (into [] (map eval-form) forms))

```

This evaluates already-read forms in sequence. Document sources should use the
document evaluator above so that evaluation can also affect how later forms are read.

The `fr.jeremyschoffen.prose.alpha.eval.common/evaluate` function is provided as a helper to tie this model together:
```clojure
(defn evaluate
  "Evaluate a sequence of forms in order. Returns the sequence of evaluations.

  To do so an evaluation context is created using [[make-evaluation-ctxt]]. This
  context is passed to [[evaluate-ctxt]] that has been wrapped with `middleware`.

  Args:
  - `ef`: an 'evaluate-form' function that take 1 form and returns the result of evaluating it.
  - `middleware`: an 'evaluate-ctxt -> evaluate-ctxt' function
  - `forms`: the sequence to forms to evaluate"
  [ef middleware forms]
  (let [ctxt (make-evaluation-ctxt ef forms)
        eval-ctxt (middleware evaluate-ctxt)]
    (eval-ctxt ctxt)))

```


The rest of the `fr.jeremyschoffen.prose.alpha.eval.common` namespace provides
tools that answer our other considerations in the form of middleware for the
`fr.jeremyschoffen.prose.alpha.eval.common/evaluate-ctxt` context function.


### Error management
We have already seen that `fr.jeremyschoffen.prose.alpha.eval.common/evaluate-ctxt` returns the
evaluation context with either an added `:result` or `:error` keys. The
middleware `fr.jeremyschoffen.prose.alpha.eval.common/wrap-eval-result` may be used to transform
`fr.jeremyschoffen.prose.alpha.eval.common/evaluate-ctxt` into a function that either returns the result
of an evaluation or throws an exception.
```clojure
(defn wrap-eval-result
  "Middleware that either returns the result of the evaluation or throws any error raised."
  [eval-ctxt]
  (fn [ctxt]
    (let [{:keys [result error]} (eval-ctxt ctxt)]
      (if result
        result
        (throw error)))))

```


### Ephemeral namespaces
The ephemeral namespace behavior is provided by the use of two different
middleware:
- `fr.jeremyschoffen.prose.alpha.eval.common/wrap-snapshot-ns` makes sure that the current namespace
  after the evaluation is the same as before:
```clojure
(defn wrap-snapshot-ns
  "Middleware making sure the current ns stays the same after an evaluation."
  [eval-ctxt]
  (fn [{:keys [eval-form] :as ctxt}]
    (let [current-ns (get-current-ns eval-form)
          ret (eval-ctxt ctxt)]
      (back-to-base-ns eval-form current-ns)
      ret)))

```

- `fr.jeremyschoffen.prose.alpha.eval.common/wrap-eval-in-temp-ns` creates an ephemeral namespace, makes
  it the current one, evaluates code then deletes it:
```clojure
(defn wrap-eval-in-temp-ns
  "Middleware that makes the evaluation take place in a temporary namespace."
  ([eval-ctxt]
   (wrap-eval-in-temp-ns eval-ctxt nil))
  ([eval-ctxt temp-ns]
   (fn [{:keys [eval-form] :as ctxt}]
     (let [temp-ns (or temp-ns (gensym "temp_ns__"))
           res (do
                 (switch-to-temp-ns eval-form temp-ns)
                 (eval-ctxt ctxt))]
       (remove-temp-ns eval-form temp-ns)
       res))))

```

### Generality of the model
This whole execution model takes an `eval-form` function as a parameter.
For instance here is `fr.jeremyschoffen.prose.alpha.eval.common/eval-forms-in-temp-ns`:
```clojure
(defn eval-forms-in-temp-ns
  "Evaluate a sequence of forms in a temporary namespace.

  Args:
  - `forms`; a sequence of forms to eval
  - `eval-form`: a function a evaluates one form defaulting to `clojure.core/eval`."
  ([forms]
   (eval-forms-in-temp-ns eval forms))
  ([eval-form forms]
   (evaluate eval-form wrap-eval-forms-in-temp-ns forms)))

```

Using anything other than the default `eval` provided by Clojure is possible.
Although not implemented here, we could use this in self-hosted ClojureScript
and that's what we do with sci. Take a look at the
`fr.jeremyschoffen.prose.alpha.eval.sci/eval-forms-in-temp-ns` function:
```clojure
(defn eval-forms-in-temp-ns
  "Evaluate a sequence of forms with sci in a temporary namespace."
  ([forms]
   (eval-forms-in-temp-ns (init nil) forms))
  ([sci-ctxt forms]
   (let [ef (sci-ctxt->sci-eval sci-ctxt)]
     (eval-common/bind-env {:prose.alpha/env :clojure-sci}
       (call-with-restored-sci-ns
        #(eval-common/eval-forms-in-temp-ns ef forms))))))

```

We can see that this function uses the machinery provided by the common
namespace (the last line of code). The `eval-form` function used named `ef` is
created with:
```clojure
(defn sci-ctxt->sci-eval
  "Make an eval function from an sci context.

  The result is a function of one argument, a `form` to be evaluated by sci in
  the evaluation context `ctxt`."
  [ctxt]
  (fn [form]
    (sci/eval-form ctxt form)))

```


### Multi-threading, Sandboxing... Sci
Sci is interesting in this project for different reasons. For instance, where
the Clojure version failed, with Sci we can do:
```clojure

(deref (future (eval-sci/eval-forms '[(+ 1 1)(+ 1 2)])))

```
;=>
```clojure
[2 3]
```

We can evaluate code in ClojureScript without having to go self hosted.

The fact that it allows us to reify execution environments (and fork them
cheaply) opens a lot of possibilities up. Evaluations can occur on any number
of threads while being in their own sandboxed environments forked from a
common one.

Also I believe that using Sci carrefully, allows us not to take too big a
performance hit. Only code declared inside documents is going to be
interpreted by Sci. Functions that a document uses and that come from
the environment (the one created with sci.core/init) are Clojure functions
and I believe run directly as Clojure code.

