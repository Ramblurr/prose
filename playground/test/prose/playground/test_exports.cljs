(ns prose.playground.test-exports
  (:require
   ["@codemirror/commands" :refer [history redo redoDepth undo undoDepth]]
   ["@codemirror/language" :refer [ensureSyntaxTree highlightingFor syntaxTree]]
   ["@codemirror/state" :refer [EditorState]]
   ["@lezer/highlight" :refer [highlightTree tagHighlighter tags]]
   [prose.playground.example-controller :as examples]
   [prose.playground.host :as host]
   [prose.playground.lozenge-shorthand :as shorthand]
   [prose.playground.preview-document :as preview-document]
   [prose.playground.prose-language :as prose-language]
   [prose.playground.protocol :as protocol]
   [prose.playground.render-controller :as render]))

(def public-seams
  #js {:EditorState EditorState
       :atInputTransaction shorthand/at-input-transaction
       :atShorthand shorthand/at-shorthand
       :balancedSyntax prose-language/balanced-syntax
       :clearPendingTransaction shorthand/clear-pending-transaction
       :clojureLanguage prose-language/clojure-language
       :createExampleController examples/create-example-controller
       :createHostActions
       (fn [^js options]
         (host/create-host-actions
          {:cancel-scheduled (.-cancelScheduled options)
           :preview-document (.-previewDocument options)
           :render (.-render options)
           :reset-example (.-resetExample options)
           :schedule-current (.-scheduleCurrent options)
           :select-example (.-selectExample options)}))
       :createRenderController render/create-render-controller
       :currentRenderResponse protocol/current-render-response
       :ensureSyntaxTree ensureSyntaxTree
       :highlightingFor highlightingFor
       :highlightTree highlightTree
       :history history
       :previewDocument preview-document/preview-document
       :proseLanguage prose-language/prose-language
       :proseTags prose-language/prose-tags
       :protocolVersion protocol/protocol-version
       :readinessState protocol/readiness-state
       :redo redo
       :redoDepth redoDepth
       :renderRequest protocol/render-request
       :shorthandPending shorthand/shorthand-pending
       :syntaxTree syntaxTree
       :tagHighlighter tagHighlighter
       :tags tags
       :undo undo
       :undoDepth undoDepth})

(set! (.-exports ^js js/module) public-seams)
