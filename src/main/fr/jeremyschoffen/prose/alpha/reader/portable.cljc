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

(def ^:private clojure-reader-error-type
  :fr.jeremyschoffen.prose.alpha.reader.core.error/clojure-reader-error)

(defn- scanner-error [start-index index expected]
  (throw (ex-info "Portable parser failure."
                  {:type ::scanner-error
                   :start-index start-index
                   :index index
                   :expected expected})))

(defn- line-start-indexes [source]
  (loop [i 0
         starts [0]]
    (if (= i (count source))
      starts
      (recur (inc i)
             (cond-> starts
               (= \newline (nth source i)) (conj (inc i)))))))

(defn- source-position [line-starts index]
  (loop [lower 0
         upper (count line-starts)]
    (if (< lower upper)
      (let [middle (quot (+ lower upper) 2)]
        (if (<= (nth line-starts middle) index)
          (recur (inc middle) upper)
          (recur lower middle)))
      (let [line-index (dec lower)]
        {:line (inc line-index)
         :column (inc (- index (nth line-starts line-index)))}))))

(defn- source-region [line-starts start-index end-index]
  (let [start (source-position line-starts start-index)
        end (source-position line-starts end-index)]
    {:start-index start-index
     :end-index end-index
     :start-line (:line start)
     :start-column (:column start)
     :end-line (:line end)
     :end-column (:column end)}))

(defn- add-source-regions [node line-starts]
  (let [node (update node :content
                     #(mapv (fn [part]
                              (if (map? part)
                                (add-source-regions part line-starts)
                                part))
                            %))
        {:keys [start-index end-index]} (meta node)]
    (with-meta node
      (merge (meta node)
             (source-region line-starts start-index end-index)))))

(defn- syntax-error
  [source line-starts {:keys [start-index end-index index expected cause]}]
  (let [end-index (or end-index
                      (if (< index (count source)) (inc index) index))
        location (source-position line-starts index)
        region (source-region line-starts start-index end-index)]
    (ex-info (str "Prose reader error at line " (:line location)
                  ", column " (:column location)
                  ": expected " expected ".")
             (merge {:type ::syntax-error
                     :source source
                     :text (subs source start-index end-index)}
                    region
                    {:index index
                     :line (:line location)
                     :column (:column location)
                     :expected expected})
             cause)))

(defn- normalized-read-error [source line-starts error]
  (let [{:keys [type start-index index expected region failure]} (ex-data error)]
    (cond
      (= type ::scanner-error)
      (syntax-error source
                    line-starts
                    {:start-index start-index
                     :index index
                     :expected expected
                     :cause error})

      (= type clojure-reader-error-type)
      (syntax-error source
                    line-starts
                    {:start-index (:start-index region)
                     :end-index (:end-index region)
                     :index (:start-index region)
                     :expected (or (-> failure ex-data :edamame/expected-delimiter)
                                   "valid Clojure syntax")
                     :cause error})

      :else
      error)))


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


(defn- symbol-end [source start failure-start expected]
  (let [name-end (simple-symbol-end source start)]
    (when-not name-end
      (scanner-error failure-start start expected))
    (if (and (< name-end (count source))
             (= slash (nth source name-end)))
      (or (simple-symbol-end source (inc name-end))
          name-end)
      name-end)))


(defn- verbatim-content [source start command-start]
  (loop [i start
         chunk-start start
         content []]
    (when (= i (count source))
      (scanner-error command-start i "\""))
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
          (scanner-error command-start i "a character after \\"))

        :else
        (recur (inc i) chunk-start content)))))


(defn- clojure-string-end [source start region-start]
  (loop [i (inc start)]
    (when (= i (count source))
      (scanner-error region-start i "\""))
    (case (nth source i)
      \" (inc i)
      \\ (if (< (inc i) (count source))
           (recur (+ i 2))
           (scanner-error region-start i "a character after \\"))
      (recur (inc i)))))


(declare command-content)


(defn- delimited-content
  [source start closing {:keys [string-protected? region-start]}]
  (let [opening (nth source start)]
    (loop [i (inc start)
           chunk-start (inc start)
           content [(str opening)]
           depth 1]
      (when (= i (count source))
        (scanner-error region-start i (str closing)))
      (let [ch (nth source i)]
        (cond
          (and string-protected? (= ch double-quote))
          (recur (clojure-string-end source i region-start)
                 chunk-start
                 content
                 depth)

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
  (let [[content end] (delimited-content source
                                         (inc command-start)
                                         \)
                                         {:string-protected? true
                                          :region-start command-start})]
    [(with-meta {:tag :clojure-call
                 :content content}
       {:start-index command-start
        :end-index end})
     end]))


(defn- symbol-node [source command-start]
  (let [start (+ command-start 2)
        end (symbol-end source start command-start "a symbol")]
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
        [content end] (delimited-content source
                                         start
                                         closing
                                         {:string-protected? string-protected?
                                          :region-start start})]
    [(with-meta {:tag tag
                 :content content}
       {:start-index start
        :end-index end})
     end]))


(defn- named-command-node [source command-start unspliced?]
  (let [name-start (+ command-start (if unspliced? 2 1))
        name-end (symbol-end source
                             name-start
                             command-start
                             "a command name")
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
    (scanner-error command-start (inc command-start) "a command"))
  (case (nth source (inc command-start))
    \" (verbatim-content source (+ command-start 2) command-start)
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


(defn form->text
  "Returns the exact region of `source` represented by `form`."
  [form source]
  (if (string? form)
    form
    (let [{:keys [start-index end-index]}
          (-> form
              meta
              :fr.jeremyschoffen.prose.alpha.reader.core/parse-region)]
      (subs source start-index end-index))))

(defn- read-from-string* [source]
  (let [line-starts (line-start-indexes source)]
    (try
      (-> source
          parse
          (add-source-regions line-starts)
          clojurizer/clojurize)
      (catch #?@(:clj [Exception error] :cljs [js/Error error])
        (throw (normalized-read-error source line-starts error))))))

(defn read-from-string
  "Reads portable Prose syntax as evaluator-neutral Clojure data.

  When supplied, `:reader-options` replaces the Edamame options for this read."
  ([source]
   (read-from-string* source))
  ([source opts]
   (binding [clojurizer/*reader-options*
             (get opts :reader-options clojurizer/*reader-options*)]
     (read-from-string* source))))
