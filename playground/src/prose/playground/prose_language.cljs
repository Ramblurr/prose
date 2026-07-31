(ns prose.playground.prose-language
  (:require
   [goog.object :as gobj]
   [prose.playground.interop :as interop]))

(def language-module (js/require "@codemirror/language"))
(def clojure-module (js/require "@codemirror/legacy-modes/mode/clojure"))
(def highlight-module (js/require "@lezer/highlight"))

(def highlight-style (gobj/get language-module "HighlightStyle"))
(def stream-language (gobj/get language-module "StreamLanguage"))
(def string-stream (gobj/get language-module "StringStream"))
(def syntax-highlighting (gobj/get language-module "syntaxHighlighting"))
(def clojure-mode (gobj/get clojure-module "clojure"))
(def tag-type (gobj/get highlight-module "Tag"))
(def tags (gobj/get highlight-module "tags"))

(def prose-tags
  (clj->js
   {:control (interop/call tag-type
                           "define"
                           "proseControl"
                           (gobj/get tags "processingInstruction"))
    :command (interop/call tag-type
                           "define"
                           "proseCommand"
                           (gobj/get tags "tagName"))
    :symbol (interop/call tag-type
                          "define"
                          "proseSymbol"
                          (gobj/get tags "variableName"))
    :delimiter (interop/call tag-type
                             "define"
                             "proseDelimiter"
                             (gobj/get tags "bracket"))
    :verbatim (interop/call tag-type
                            "define"
                            "proseVerbatim"
                            (gobj/get tags "string"))}))

(def balanced-highlight-style
  (interop/call
   highlight-style
   "define"
   (clj->js
    [{:tag (gobj/get prose-tags "control")
      :class "tok-prose-control"}
     {:tag (gobj/get prose-tags "command")
      :class "tok-prose-command"}
     {:tag (gobj/get prose-tags "symbol")
      :class "tok-prose-symbol"}
     {:tag (gobj/get prose-tags "delimiter")
      :class "tok-prose-delimiter"}
     {:tag (gobj/get prose-tags "verbatim")
      :class "tok-prose-verbatim"}
     {:tag (interop/call tags "standard" (gobj/get tags "variableName"))
      :class "tok-clj-operator"}
     {:tag [(gobj/get tags "keyword")
            (gobj/get tags "controlKeyword")
            (gobj/get tags "definitionKeyword")
            (gobj/get tags "moduleKeyword")]
      :class "tok-clj-keyword"}
     {:tag [(gobj/get tags "atom")
            (gobj/get tags "bool")
            (gobj/get tags "null")]
      :class "tok-clj-atom"}
     {:tag (gobj/get tags "variableName")
      :class "tok-clj-symbol"}
     {:tag [(gobj/get tags "string")
            (gobj/get tags "character")]
      :class "tok-clj-string"}
     {:tag (gobj/get tags "number")
      :class "tok-clj-number"}
     {:tag (gobj/get tags "comment")
      :class "tok-clj-comment"}
     {:tag (gobj/get tags "bracket")
      :class "tok-clj-bracket"}
     {:tag (gobj/get tags "meta")
      :class "tok-clj-meta"}
     {:tag (gobj/get tags "invalid")
      :class "tok-invalid"}])))

(def balanced-syntax
  (interop/invoke syntax-highlighting balanced-highlight-style))

(def whitespace
  #{"\t" "\n" "\u000B" "\f" "\r" " " "\u00a0" "\u1680"
    "\u2000" "\u2001" "\u2002" "\u2003" "\u2004" "\u2005"
    "\u2006" "\u2007" "\u2008" "\u2009" "\u200a" "\u2028"
    "\u2029" "\u202f" "\u205f" "\u3000"})

(def symbol-delimiters #{"(" ")" "[" "]" "{" "}" "\""})

(def clojure-base-tokenizer
  (gobj/get (interop/call clojure-mode "startState" 2) "tokenize"))

(defn whitespace-character? [character]
  (contains? whitespace character))

(defn regular-symbol-character? [character]
  (and (some? character)
       (not (whitespace-character? character))
       (not= "◊" character)
       (not= "/" character)
       (not= "\\" character)
       (not (contains? symbol-delimiters character))))

(defn first-symbol-character? [character]
  (and (regular-symbol-character? character)
       (not= "#" character)
       (not (re-find #"[0-9]" character))))

(defn simple-symbol-end [text start]
  (when (first-symbol-character? (aget text start))
    (loop [index (inc start)]
      (if (regular-symbol-character? (aget text index))
        (recur (inc index))
        index))))

(defn symbol-length [text]
  (if-let [first-end (simple-symbol-end text 0)]
    (if (= "/" (aget text first-end))
      (or (simple-symbol-end text (inc first-end)) first-end)
      first-end)
    0))

(defn clone-context [context]
  (when context
    (let [copy (gobj/clone context)]
      (gobj/set copy "prev" (clone-context (gobj/get context "prev")))
      copy)))

(defn clone-clojure-state [state]
  (let [copy (gobj/clone state)]
    (gobj/set copy "ctx" (clone-context (gobj/get state "ctx")))
    copy))

(defn clojure-frame
  ([closing indent-unit]
   (clojure-frame closing indent-unit false))
  ([closing indent-unit finish-command?]
   (let [state (interop/call clojure-mode "startState" indent-unit)
         opening (if (= ")" closing) "(" "[")]
     (interop/call clojure-mode
                   "token"
                   (js/Reflect.construct string-stream #js [opening])
                   state)
     (clj->js
      {:kind "clojure"
       :closing closing
       :finishCommand finish-command?
       :state state}))))

(defn text-frame
  ([]
   (text-frame nil))
  ([closing]
   (clj->js
    {:kind "text"
     :closing closing
     :depth (if closing 1 0)})))

(defn command-frame []
  (clj->js {:kind "command" :stage "after-introducer"}))

(defn copy-frame [frame]
  (let [copy (gobj/clone frame)]
    (when (= "clojure" (gobj/get frame "kind"))
      (gobj/set copy "state" (clone-clojure-state (gobj/get frame "state"))))
    copy))

(defn close-child-frame [state frame]
  (.pop (gobj/get state "frames"))
  (when (gobj/get frame "finishCommand")
    (.pop (gobj/get state "frames"))))

(defn consume-text [stream state frame]
  (let [character (interop/call stream "peek")]
    (cond
      (= "◊" character)
      (do
        (interop/call stream "next")
        (.push (gobj/get state "frames") (command-frame))
        "prose-control")

      (and (gobj/get frame "closing") (= "{" character))
      (do
        (interop/call stream "next")
        (gobj/set frame "depth" (inc (gobj/get frame "depth")))
        "prose-delimiter")

      (and (gobj/get frame "closing") (= "}" character))
      (do
        (interop/call stream "next")
        (gobj/set frame "depth" (dec (gobj/get frame "depth")))
        (when (zero? (gobj/get frame "depth"))
          (.pop (gobj/get state "frames")))
        "prose-delimiter")

      :else
      (do
        (loop []
          (when-not (interop/call stream "eol")
            (let [next-character (interop/call stream "peek")]
              (when-not (or (= "◊" next-character)
                            (and (gobj/get frame "closing")
                                 (#{"{" "}"} next-character)))
                (interop/call stream "next")
                (recur)))))
        nil))))

(defn consume-clojure [stream state frame]
  (let [clojure-state (gobj/get frame "state")
        at-code-boundary? (identical? (gobj/get clojure-state "tokenize")
                                      clojure-base-tokenizer)
        character (interop/call stream "peek")
        context (gobj/get clojure-state "ctx")
        previous-context (when context (gobj/get context "prev"))]
    (cond
      (and at-code-boundary? (= "◊" character))
      (do
        (interop/call stream "next")
        (.push (gobj/get state "frames") (command-frame))
        "prose-control")

      (and at-code-boundary?
           (= character (gobj/get frame "closing"))
           previous-context
           (nil? (gobj/get previous-context "prev")))
      (do
        (interop/call stream "next")
        (close-child-frame state frame)
        "prose-delimiter")

      :else
      (let [position (gobj/get stream "pos")
            line (gobj/get stream "string")
            prose-boundary (if (and at-code-boundary? (not= ";" character))
                             (.indexOf line "◊" position)
                             -1)]
        (if (<= prose-boundary position)
          (interop/call clojure-mode "token" stream clojure-state)
          (try
            (gobj/set stream "string" (.slice line 0 prose-boundary))
            (interop/call clojure-mode "token" stream clojure-state)
            (finally
              (gobj/set stream "string" line))))))))

(defn consume-verbatim [stream state]
  (loop [escaped? false]
    (if (interop/call stream "eol")
      "prose-verbatim"
      (let [character (interop/call stream "next")]
        (if (and (= "\"" character) (not escaped?))
          (do
            (.pop (gobj/get state "frames"))
            "prose-verbatim")
          (recur (and (not escaped?) (= "\\" character))))))))

(defn consume-whitespace [stream]
  (loop []
    (when (and (not (interop/call stream "eol"))
               (whitespace-character? (interop/call stream "peek")))
      (interop/call stream "next")
      (recur))))

(defn consume-command [stream state frame]
  (let [stage (gobj/get frame "stage")]
    (cond
      (= "verbatim" stage)
      (consume-verbatim stream state)

      (= "after-introducer" stage)
      (let [character (interop/call stream "peek")]
        (case character
          "◊"
          (do
            (interop/call stream "next")
            (gobj/set frame "stage" "name")
            "prose-control")

          "|"
          (do
            (interop/call stream "next")
            (gobj/set frame "stage" "symbol")
            "prose-control")

          "\""
          (do
            (interop/call stream "next")
            (gobj/set frame "stage" "verbatim")
            "prose-verbatim")

          "("
          (do
            (interop/call stream "next")
            (gobj/set frame "stage" "child-completes-command")
            (.push (gobj/get state "frames")
                   (clojure-frame ")" (gobj/get state "indentUnit") true))
            "prose-delimiter")

          (do
            (gobj/set frame "stage" "name")
            nil)))

      (#{"name" "symbol"} stage)
      (let [position (gobj/get stream "pos")
            length (symbol-length (.slice (gobj/get stream "string") position))]
        (if (zero? length)
          (do
            (when-not (interop/call stream "eol")
              (interop/call stream "next"))
            (.pop (gobj/get state "frames"))
            "invalid")
          (do
            (gobj/set stream "pos" (+ position length))
            (if (= "name" stage)
              (gobj/set frame "stage" "after-argument")
              (.pop (gobj/get state "frames")))
            (if (= "name" stage) "prose-command" "prose-symbol"))))

      (= "after-argument" stage)
      (if (whitespace-character? (interop/call stream "peek"))
        (do
          (consume-whitespace stream)
          nil)
        (case (interop/call stream "peek")
          "["
          (do
            (interop/call stream "next")
            (.push (gobj/get state "frames")
                   (clojure-frame "]" (gobj/get state "indentUnit")))
            "prose-delimiter")

          "{"
          (do
            (interop/call stream "next")
            (.push (gobj/get state "frames") (text-frame "}"))
            "prose-delimiter")

          (do
            (.pop (gobj/get state "frames"))
            nil)))

      :else
      (do
        (.pop (gobj/get state "frames"))
        nil))))

(def prose-stream-parser
  (clj->js
   {:name "prose"
    :startState
    (fn [indent-unit]
      (clj->js {:indentUnit indent-unit :frames [(text-frame)]}))
    :copyState
    (fn [state]
      (let [copy (gobj/clone state)]
        (gobj/set copy
                  "frames"
                  (.map (gobj/get state "frames") copy-frame))
        copy))
    :token
    (fn [stream state]
      (loop [guard 0]
        (if (< guard 12)
          (let [start (gobj/get stream "pos")
                frames (gobj/get state "frames")
                frame (or (aget frames (dec (.-length frames))) (text-frame))]
            (when (zero? (.-length frames))
              (.push frames frame))
            (let [style (case (gobj/get frame "kind")
                          "text" (consume-text stream state frame)
                          "clojure" (consume-clojure stream state frame)
                          (consume-command stream state frame))]
              (if (or (> (gobj/get stream "pos") start) style)
                style
                (recur (inc guard)))))
          (do
            (interop/call stream "next")
            "invalid"))))
    :tokenTable
    {:prose-control (gobj/get prose-tags "control")
     :prose-command (gobj/get prose-tags "command")
     :prose-symbol (gobj/get prose-tags "symbol")
     :prose-delimiter (gobj/get prose-tags "delimiter")
     :prose-verbatim (gobj/get prose-tags "verbatim")}
    :mergeTokens false}))

(def prose-language
  (interop/call stream-language "define" prose-stream-parser))

(def clojure-language
  (interop/call stream-language "define" clojure-mode))
