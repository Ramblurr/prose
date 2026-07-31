(ns prose.playground.test-exports
  (:require
   [prose.playground.example-controller :as examples]
   [prose.playground.lozenge-shorthand :as shorthand]
   [prose.playground.preview-document :as preview-document]
   [prose.playground.prose-language :as prose-language]
   [prose.playground.protocol :as protocol]
   [prose.playground.render-controller :as render]))

(def commands-module (js/require "@codemirror/commands"))
(def language-module (js/require "@codemirror/language"))
(def state-module (js/require "@codemirror/state"))
(def highlight-module (js/require "@lezer/highlight"))

(def public-seams
  (clj->js
   {:EditorState (aget state-module "EditorState")
    :atInputTransaction shorthand/at-input-transaction
    :atShorthand shorthand/at-shorthand
    :balancedSyntax prose-language/balanced-syntax
    :clearPendingTransaction shorthand/clear-pending-transaction
    :clojureLanguage prose-language/clojure-language
    :createExampleController examples/create-example-controller
    :createRenderController render/create-render-controller
    :currentRenderResponse protocol/current-render-response
    :ensureSyntaxTree (aget language-module "ensureSyntaxTree")
    :highlightingFor (aget language-module "highlightingFor")
    :highlightTree (aget highlight-module "highlightTree")
    :history (aget commands-module "history")
    :previewDocument preview-document/preview-document
    :proseLanguage prose-language/prose-language
    :proseTags prose-language/prose-tags
    :protocolVersion protocol/protocol-version
    :readinessState protocol/readiness-state
    :redo (aget commands-module "redo")
    :redoDepth (aget commands-module "redoDepth")
    :renderRequest protocol/render-request
    :shorthandPending shorthand/shorthand-pending
    :syntaxTree (aget language-module "syntaxTree")
    :tagHighlighter (aget highlight-module "tagHighlighter")
    :tags (aget highlight-module "tags")
    :undo (aget commands-module "undo")
    :undoDepth (aget commands-module "undoDepth")}))

(aset js/module "exports" public-seams)
