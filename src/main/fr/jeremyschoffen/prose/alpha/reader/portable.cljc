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


(defn- delimited-content [source start closing string-protected?]
  (let [opening (nth source start)]
    (loop [i (inc start)
           chunk-start (inc start)
           content [(str opening)]
           depth 1]
      (when (= i (count source))
        (throw (ex-info "Expected a closing delimiter."
                        {:index i
                         :expected closing})))
      (let [ch (nth source i)]
        (cond
          (and string-protected? (= ch double-quote))
          (recur (clojure-string-end source i) chunk-start content depth)

          (= ch special)
          (let [[embedded end] (command-content source i)
                content (cond-> content
                          (< chunk-start i) (conj (subs source chunk-start i)))]
            (recur end end (into content embedded) depth))

          (= ch opening)
          (recur (inc i)
                 (inc i)
                 (cond-> content
                   (< chunk-start i) (conj (subs source chunk-start i))
                   true (conj (str opening)))
                 (inc depth))

          (= ch closing)
          (let [content (cond-> content
                          (< chunk-start i) (conj (subs source chunk-start i))
                          true (conj (str closing)))]
            (if (= depth 1)
              [content (inc i)]
              (recur (inc i) (inc i) content (dec depth))))

          :else
          (recur (inc i) chunk-start content depth))))))


(defn- clojure-call-node [source command-start]
  (let [[content end] (delimited-content source (inc command-start) \) true)]
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


(defn- tag-argument-node [source start]
  (let [[tag closing string-protected?]
        (case (nth source start)
          \[ [:tag-clj-arg \] true]
          \{ [:tag-text-arg \} false])
        [content end] (delimited-content source start closing string-protected?)]
    [(with-meta {:tag tag
                 :content content}
       {:start-index start
        :end-index end})
     end]))


(defn- named-command-node [source command-start unspliced?]
  (let [name-start (+ command-start (if unspliced? 2 1))
        name-end (symbol-end source name-start)
        name-node (with-meta {:tag :tag-name
                              :content [(subs source name-start name-end)]}
                    {:start-index name-start
                     :end-index name-end})]
    (loop [i name-end
           content [name-node]]
      (let [argument-start
            (loop [j i]
              (if (and (< j (count source))
                       (whitespace? (nth source j)))
                (recur (inc j))
                j))]
        (if (and (< argument-start (count source))
                 (contains? #{\[ \{} (nth source argument-start)))
          (let [[argument end] (tag-argument-node source argument-start)]
            (recur end (conj content argument)))
          [(with-meta {:tag (if unspliced? :tag-unspliced :tag)
                       :content content}
             {:start-index command-start
              :end-index i})
           i])))))


(defn- command-content [source command-start]
  (when (= (inc command-start) (count source))
    (throw (ex-info "Expected a command after ◊." {:index command-start})))
  (case (nth source (inc command-start))
    \" (verbatim-content source (+ command-start 2))
    \( (let [[node end] (clojure-call-node source command-start)]
         [[node] end])
    \| (let [[node end] (symbol-node source command-start)]
         [[node] end])
    \◊ (let [[node end] (named-command-node source command-start true)]
         [[node] end])
    (let [[node end] (named-command-node source command-start false)]
      [[node] end])))


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
