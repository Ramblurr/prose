(ns fr.jeremyschoffen.prose.alpha.reader.portable
  "Portable replacement path for reading Prose documents."
  (:require
    [fr.jeremyschoffen.prose.alpha.reader.clojurizer :as clojurizer]))


(def ^:private special \◊)
(def ^:private escape \\)
(def ^:private double-quote \")
(def ^:private slash \/)
(def ^:private macro-reader-char \#)


(def ^:private whitespace-chars
  #{\tab \newline \u000B \formfeed \return \space
    \u00A0 \u1680
    \u2000 \u2001 \u2002 \u2003 \u2004 \u2005 \u2006 \u2007 \u2008 \u2009 \u200A
    \u2028 \u2029 \u202F \u205F \u3000})


(def ^:private symbol-delimiters
  #{\( \) \[ \] \{ \} \"})


(def ^:private digits
  #{\0 \1 \2 \3 \4 \5 \6 \7 \8 \9})


(defn- whitespace? [ch]
  (contains? whitespace-chars ch))


(defn- symbol-regular-char? [ch]
  (and ch
       (not (whitespace? ch))
       (not= ch special)
       (not= ch slash)
       (not= ch escape)
       (not (contains? symbol-delimiters ch))))


(defn- symbol-first-char? [ch]
  (and (symbol-regular-char? ch)
       (not= ch macro-reader-char)
       (not (contains? digits ch))))


(defn- plain-text-end [source start]
  (loop [i start]
    (if (or (= i (count source))
            (= special (nth source i)))
      i
      (recur (inc i)))))


(defn- simple-symbol-end [source start]
  (when (and (< start (count source))
             (symbol-first-char? (nth source start)))
    (loop [i (inc start)]
      (if (and (< i (count source))
               (symbol-regular-char? (nth source i)))
        (recur (inc i))
        i))))


(defn- symbol-end [source start]
  (let [name-end (simple-symbol-end source start)]
    (when-not name-end
      (throw (ex-info "Expected a symbol after ◊|." {:index start})))
    (if (and (< name-end (count source))
             (= slash (nth source name-end)))
      (or (simple-symbol-end source (inc name-end))
          name-end)
      name-end)))


(defn- verbatim-content [source start]
  (loop [i start
         chunk-start start
         content []]
    (when (= i (count source))
      (throw (ex-info "Expected a closing quote." {:index i})))
    (let [ch (nth source i)]
      (cond
        (= ch double-quote)
        [(cond-> content
           (< chunk-start i) (conj (subs source chunk-start i)))
         (inc i)]

        (= ch escape)
        (if (< (inc i) (count source))
          (recur (+ i 2)
                 (+ i 2)
                 (cond-> content
                   (< chunk-start i) (conj (subs source chunk-start i))
                   true (conj (str (nth source (inc i))))))
          (throw (ex-info "Expected a character after an escape." {:index i})))

        :else
        (recur (inc i) chunk-start content)))))


(defn- clojure-string-end [source start]
  (loop [i (inc start)]
    (when (= i (count source))
      (throw (ex-info "Expected a closing quote." {:index i})))
    (case (nth source i)
      \" (inc i)
      \\ (if (< (inc i) (count source))
           (recur (+ i 2))
           (throw (ex-info "Expected a character after an escape." {:index i})))
      (recur (inc i)))))


(declare command-content)


(defn- clojure-call-content [source command-start]
  (loop [i (+ command-start 2)
         chunk-start (inc command-start)
         content []
         depth 1]
    (when (= i (count source))
      (throw (ex-info "Expected a closing parenthesis." {:index i})))
    (case (nth source i)
      \" (recur (clojure-string-end source i) chunk-start content depth)
      \◊ (let [[embedded end] (command-content source i)
               content (cond-> content
                         (< chunk-start i) (conj (subs source chunk-start i)))]
           (recur end end (into content embedded) depth))
      \( (recur (inc i) chunk-start content (inc depth))
      \) (if (= depth 1)
           [(cond-> content
              (< chunk-start (inc i)) (conj (subs source chunk-start (inc i))))
            (inc i)]
           (recur (inc i) chunk-start content (dec depth)))
      (recur (inc i) chunk-start content depth))))


(defn- clojure-call-node [source command-start]
  (let [[content end] (clojure-call-content source command-start)]
    [(with-meta {:tag :clojure-call
                 :content content}
       {:start-index command-start
        :end-index end})
     end]))


(defn- symbol-node [source command-start]
  (let [start (+ command-start 2)
        end (symbol-end source start)]
    [(with-meta {:tag :symbol-use
                 :content [(subs source start end)]}
       {:start-index command-start
        :end-index end})
     end]))


(defn- command-content [source command-start]
  (when (= (inc command-start) (count source))
    (throw (ex-info "Expected a command after ◊." {:index command-start})))
  (case (nth source (inc command-start))
    \" (verbatim-content source (+ command-start 2))
    \( (let [[node end] (clojure-call-node source command-start)]
         [[node] end])
    \| (let [[node end] (symbol-node source command-start)]
         [[node] end])
    (throw (ex-info "Unsupported portable reader command." {:index command-start}))))


(defn- parse [source]
  (let [length (count source)]
    (loop [i 0
           content []]
      (if (= i length)
        (with-meta {:tag :doc
                    :content content}
          {:start-index 0
           :end-index length})
        (if (= special (nth source i))
          (let [[embedded end] (command-content source i)]
            (recur (long end) (into content embedded)))
          (let [end (plain-text-end source i)]
            (recur (long end) (conj content (subs source i end)))))))))


(defn read-from-string
  "Reads portable Prose syntax as evaluator-neutral Clojure data.

  When supplied, `:reader-options` replaces the Edamame options for this read."
  ([source]
   (-> source
       parse
       clojurizer/clojurize))
  ([source opts]
   (binding [clojurizer/*reader-options*
             (get opts :reader-options clojurizer/*reader-options*)]
     (read-from-string source))))
