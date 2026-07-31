(ns prose.playground.prose-language
  (:require
   ["@codemirror/language" :refer [HighlightStyle StreamLanguage StringStream syntaxHighlighting]
    :rename {HighlightStyle highlight-style
             StreamLanguage stream-language
             syntaxHighlighting syntax-highlighting}]
   ["@codemirror/legacy-modes/mode/clojure" :refer [clojure]
    :rename {clojure clojure-mode}]
   ["@lezer/highlight" :refer [Tag tags]
    :rename {Tag tag-type}]
   [goog.object :as gobj]))

(def ^js highlight-tags tags)

(def ^js prose-tags
  #js {:control (.define ^js tag-type
                         "proseControl"
                         (.-processingInstruction highlight-tags))
       :command (.define ^js tag-type
                         "proseCommand"
                         (.-tagName highlight-tags))
       :symbol (.define ^js tag-type
                        "proseSymbol"
                        (.-variableName highlight-tags))
       :delimiter (.define ^js tag-type
                           "proseDelimiter"
                           (.-bracket highlight-tags))
       :verbatim (.define ^js tag-type
                          "proseVerbatim"
                          (.-string highlight-tags))})

(def balanced-highlight-style
  (.define
   ^js highlight-style
   #js [#js {:tag (.-control prose-tags)
             :class "tok-prose-control"}
        #js {:tag (.-command prose-tags)
             :class "tok-prose-command"}
        #js {:tag (.-symbol prose-tags)
             :class "tok-prose-symbol"}
        #js {:tag (.-delimiter prose-tags)
             :class "tok-prose-delimiter"}
        #js {:tag (.-verbatim prose-tags)
             :class "tok-prose-verbatim"}
        #js {:tag (.standard highlight-tags (.-variableName highlight-tags))
             :class "tok-clj-operator"}
        #js {:tag #js [(.-keyword highlight-tags)
                       (.-controlKeyword highlight-tags)
                       (.-definitionKeyword highlight-tags)
                       (.-moduleKeyword highlight-tags)]
             :class "tok-clj-keyword"}
        #js {:tag #js [(.-atom highlight-tags)
                       (.-bool highlight-tags)
                       (.-null highlight-tags)]
             :class "tok-clj-atom"}
        #js {:tag (.-variableName highlight-tags)
             :class "tok-clj-symbol"}
        #js {:tag #js [(.-string highlight-tags)
                       (.-character highlight-tags)]
             :class "tok-clj-string"}
        #js {:tag (.-number highlight-tags)
             :class "tok-clj-number"}
        #js {:tag (.-comment highlight-tags)
             :class "tok-clj-comment"}
        #js {:tag (.-bracket highlight-tags)
             :class "tok-clj-bracket"}
        #js {:tag (.-meta highlight-tags)
             :class "tok-clj-meta"}
        #js {:tag (.-invalid highlight-tags)
             :class "tok-invalid"}]))

(def balanced-syntax
  (syntax-highlighting balanced-highlight-style))

(def whitespace
  #{"\t" "\n" "\u000B" "\f" "\r" " " "\u00a0" "\u1680"
    "\u2000" "\u2001" "\u2002" "\u2003" "\u2004" "\u2005"
    "\u2006" "\u2007" "\u2008" "\u2009" "\u200a" "\u2028"
    "\u2029" "\u202f" "\u205f" "\u3000"})

(def symbol-delimiters #{"(" ")" "[" "]" "{" "}" "\""})

(def clojure-base-tokenizer
  (let [^js state (.startState ^js clojure-mode 2)]
    (.-tokenize state)))

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

(defn clone-context [^js context]
  (when context
    (let [^js copy (gobj/clone context)]
      (set! (.-prev copy) (clone-context (.-prev context)))
      copy)))

(defn clone-clojure-state [^js state]
  (let [^js copy (gobj/clone state)]
    (set! (.-ctx copy) (clone-context (.-ctx state)))
    copy))

(defn clojure-frame
  ([closing indent-unit]
   (clojure-frame closing indent-unit false))
  ([closing indent-unit finish-command?]
   (let [^js state (.startState ^js clojure-mode indent-unit)
         opening (if (= ")" closing) "(" "[")]
     (.token ^js clojure-mode (StringStream. opening) state)
     #js {:kind "clojure"
          :closing closing
          :finishCommand finish-command?
          :state state})))

(defn text-frame
  ([]
   (text-frame nil))
  ([closing]
   #js {:kind "text"
        :closing closing
        :depth (if closing 1 0)}))

(defn command-frame []
  #js {:kind "command" :stage "after-introducer"})

(defn copy-frame [^js frame]
  (let [^js copy (gobj/clone frame)]
    (when (= "clojure" (.-kind frame))
      (set! (.-state copy) (clone-clojure-state (.-state frame))))
    copy))

(defn close-child-frame [^js state ^js frame]
  (.pop (.-frames state))
  (when (.-finishCommand frame)
    (.pop (.-frames state))))

(defn consume-text [^js stream ^js state ^js frame]
  (let [character (.peek stream)]
    (cond
      (= "◊" character)
      (do
        (.next stream)
        (.push (.-frames state) (command-frame))
        "prose-control")

      (and (.-closing frame) (= "{" character))
      (do
        (.next stream)
        (set! (.-depth frame) (inc (.-depth frame)))
        "prose-delimiter")

      (and (.-closing frame) (= "}" character))
      (do
        (.next stream)
        (set! (.-depth frame) (dec (.-depth frame)))
        (when (zero? (.-depth frame))
          (.pop (.-frames state)))
        "prose-delimiter")

      :else
      (do
        (loop []
          (when-not (.eol stream)
            (let [next-character (.peek stream)]
              (when-not (or (= "◊" next-character)
                            (and (.-closing frame)
                                 (#{"{" "}"} next-character)))
                (.next stream)
                (recur)))))
        nil))))

(defn consume-clojure [^js stream ^js state ^js frame]
  (let [^js clojure-state (.-state frame)
        at-code-boundary? (identical? (.-tokenize clojure-state)
                                      clojure-base-tokenizer)
        character (.peek stream)
        ^js context (.-ctx clojure-state)
        ^js previous-context (when context (.-prev context))]
    (cond
      (and at-code-boundary? (= "◊" character))
      (do
        (.next stream)
        (.push (.-frames state) (command-frame))
        "prose-control")

      (and at-code-boundary?
           (= character (.-closing frame))
           previous-context
           (nil? (.-prev previous-context)))
      (do
        (.next stream)
        (close-child-frame state frame)
        "prose-delimiter")

      :else
      (let [position (.-pos stream)
            line (.-string stream)
            prose-boundary (if (and at-code-boundary? (not= ";" character))
                             (.indexOf line "◊" position)
                             -1)]
        (if (<= prose-boundary position)
          (.token ^js clojure-mode stream clojure-state)
          (try
            (set! (.-string stream) (.slice line 0 prose-boundary))
            (.token ^js clojure-mode stream clojure-state)
            (finally
              (set! (.-string stream) line))))))))

(defn consume-verbatim [^js stream ^js state]
  (loop [escaped? false]
    (if (.eol stream)
      "prose-verbatim"
      (let [character (.next stream)]
        (if (and (= "\"" character) (not escaped?))
          (do
            (.pop (.-frames state))
            "prose-verbatim")
          (recur (and (not escaped?) (= "\\" character))))))))

(defn consume-whitespace [^js stream]
  (loop []
    (when (and (not (.eol stream))
               (whitespace-character? (.peek stream)))
      (.next stream)
      (recur))))

(defn consume-command [^js stream ^js state ^js frame]
  (let [stage (.-stage frame)]
    (cond
      (= "verbatim" stage)
      (consume-verbatim stream state)

      (= "after-introducer" stage)
      (let [character (.peek stream)]
        (case character
          "◊"
          (do
            (.next stream)
            (set! (.-stage frame) "name")
            "prose-control")

          "|"
          (do
            (.next stream)
            (set! (.-stage frame) "symbol")
            "prose-control")

          "\""
          (do
            (.next stream)
            (set! (.-stage frame) "verbatim")
            "prose-verbatim")

          "("
          (do
            (.next stream)
            (set! (.-stage frame) "child-completes-command")
            (.push (.-frames state)
                   (clojure-frame ")" (.-indentUnit state) true))
            "prose-delimiter")

          (do
            (set! (.-stage frame) "name")
            nil)))

      (#{"name" "symbol"} stage)
      (let [position (.-pos stream)
            length (symbol-length (.slice (.-string stream) position))]
        (if (zero? length)
          (do
            (when-not (.eol stream)
              (.next stream))
            (.pop (.-frames state))
            "invalid")
          (do
            (set! (.-pos stream) (+ position length))
            (if (= "name" stage)
              (set! (.-stage frame) "after-argument")
              (.pop (.-frames state)))
            (if (= "name" stage) "prose-command" "prose-symbol"))))

      (= "after-argument" stage)
      (if (whitespace-character? (.peek stream))
        (do
          (consume-whitespace stream)
          nil)
        (case (.peek stream)
          "["
          (do
            (.next stream)
            (.push (.-frames state)
                   (clojure-frame "]" (.-indentUnit state)))
            "prose-delimiter")

          "{"
          (do
            (.next stream)
            (.push (.-frames state) (text-frame "}"))
            "prose-delimiter")

          (do
            (.pop (.-frames state))
            nil)))

      :else
      (do
        (.pop (.-frames state))
        nil))))

(def ^js prose-stream-parser
  #js {:name "prose"
       :startState (fn [indent-unit]
                     #js {:indentUnit indent-unit
                          :frames #js [(text-frame)]})
       :copyState (fn [^js state]
                    (let [^js copy (gobj/clone state)]
                      (set! (.-frames copy) (.map (.-frames state) copy-frame))
                      copy))
       :token (fn [^js stream ^js state]
                (loop [guard 0]
                  (if (< guard 12)
                    (let [start (.-pos stream)
                          ^js frames (.-frames state)
                          ^js frame (or (aget frames (dec (.-length frames)))
                                        (text-frame))]
                      (when (zero? (.-length frames))
                        (.push frames frame))
                      (let [style (case (.-kind frame)
                                    "text" (consume-text stream state frame)
                                    "clojure" (consume-clojure stream state frame)
                                    (consume-command stream state frame))]
                        (if (or (> (.-pos stream) start) style)
                          style
                          (recur (inc guard)))))
                    (do
                      (.next stream)
                      "invalid"))))
       :tokenTable #js {:prose-control (.-control prose-tags)
                        :prose-command (.-command prose-tags)
                        :prose-symbol (.-symbol prose-tags)
                        :prose-delimiter (.-delimiter prose-tags)
                        :prose-verbatim (.-verbatim prose-tags)}
       :mergeTokens false})

(def prose-language
  (.define stream-language prose-stream-parser))

(def clojure-language
  (.define stream-language clojure-mode))
