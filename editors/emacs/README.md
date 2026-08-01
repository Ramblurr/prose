# clojure-prose-mode

An Emacs mode for editing [Prose documents][prose] with host-language and embedded Clojure support.

The mode provides

- highlighting for Prose commands and locally detectable malformed syntax,
- host-mode selection based on the filename before its final `.prose` suffix,
- recursive embedded Clojure editing through Polymode and Clojure Mode, and
- electric lozenge entry: `@` inserts `◊`, while `@@` inserts `@`.

## Install the package

Clojure Prose Mode requires Emacs 25.1 or newer, Polymode 0.2.2 or newer, and Clojure Mode 5.20.0 or newer.

### Vanilla Emacs with `package-vc`

Emacs 29.1 and newer can install the package directly from the Prose repository:

Ensure [MELPA](https://melpa.org/#/getting-started) is configured so `package.el` can resolve the declared dependencies.

```elisp
(package-vc-install
 '(clojure-prose-mode
   :url "https://github.com/ramblurr/prose"
   :lisp-dir "editors/emacs"
   :main-file "clojure-prose-mode.el"))

(require 'clojure-prose-mode)
```

### straight.el

```elisp
(straight-use-package
 '(clojure-prose-mode
   :type git
   :host github
   :repo "ramblurr/prose"
   :files ("editors/emacs/clojure-prose-mode.el")))
```

### Doom Emacs

Add the package recipe to `packages.el`:

```elisp
(package! clojure-prose-mode
  :recipe (:host github
           :repo "ramblurr/prose"
           :files ("editors/emacs/clojure-prose-mode.el")))
```

Run `doom sync`, then load the mode from `config.el` or a language module:

```elisp
(use-package! clojure-prose-mode
  :mode ("\\.prose\\'" . clojure-prose-mode))
```

### Local checkout

Load the package directly while developing it:

```elisp
(add-to-list 'load-path "/absolute/path/to/prose/editors/emacs")
(require 'clojure-prose-mode)
```

Opening a `.prose` file activates Clojure Prose Mode; for a compound name such as `document.md.prose`, the preceding extension selects the host mode.

## Typing the lozenge

By default, `@` inserts `◊` and `@@` inserts `@`. To add a dedicated key, bind
`clojure-prose-insert-lozenge`:

```elisp
(define-key clojure-prose-mode-map (kbd "C-c l")
  #'clojure-prose-insert-lozenge)
```

## Syntax warnings

The mode flags broken commands, unfinished strings, and unclosed forms as you
type. Run Prose itself to catch anything the mode misses.

[prose]: https://github.com/ramblurr/prose
