(ns prose.playground.worker
  (:require
    [clojure.string]
    [fr.jeremyschoffen.prose.alpha.document.lib]
    [fr.jeremyschoffen.prose.alpha.document.sci :as document :include-macros true]
    [fr.jeremyschoffen.prose.alpha.eval.sci :as eval-sci]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.html.tags]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]
    [sci.core :as sci]))

(def ^:private protocol-version 1)

(def ^:private approved-core-symbols
  '#{* *ns* + - -> ->> / < <= = == > >=
     and apply as-> assoc assoc-in atom boolean case catch comp complement concat cond cond-> cond->> conj cons
     constantly
     contains? count dec def defn defn- deref disj dissoc distinct do doseq empty empty? every? false? filter finally
     first fn fn* fn? for frequencies get get-in group-by identity if if-let if-not if-some inc in-ns int into
     juxt keep keep-indexed keys keyword keyword? last lazy-seq let letfn list loop loop* map map-indexed mapcat
     mapv max
     merge merge-with meta min name namespace neg? next nil? not not-any? not-empty not-every? not= ns nth or
     partial partition partition-all partition-by peek pop pos? pr-str quote range recur reduce reduce-kv reduced
     remove remove-ns repeat repeatedly replace require reset! rest reverse second select-keys seq sequential?
     set some some-> some->> sort sort-by str string? subs subvec swap! symbol symbol? take take-last take-nth
     take-while throw true? try unchecked-inc update update-in vals vary-meta vec vector when when-first when-let
     when-not
     when-some with-meta zero? zipmap
     chunk chunk-append chunk-buffer chunk-cons chunk-first chunk-next chunk-rest chunked-seq?})

(def ^:private approved-namespaces
  (document/make-ns-bindings
    clojure.string
    fr.jeremyschoffen.prose.alpha.document.lib
    fr.jeremyschoffen.prose.alpha.out.html.tags))

(def ^:private approved-symbols
  (into approved-core-symbols
        (mapcat (fn [[namespace bindings]]
                  (mapcat (fn [binding]
                            [binding (symbol (str namespace) (str binding))])
                          (keys bindings))))
        approved-namespaces))

(def ^:private base-context
  (document/init {:allow approved-symbols
                  :namespaces approved-namespaces}))

(defn- deepest-message [error]
  (loop [current error
         message (or (ex-message error) (.-message error) (str error))]
    (if-let [cause (ex-cause current)]
      (recur cause (or (ex-message cause) message))
      message)))

(defn- source-range [data]
  (when (every? #(contains? data %)
                [:start-index :end-index :start-line :start-column :end-line :end-column])
    {:startIndex (:start-index data)
     :endIndex (:end-index data)
     :startLine (:start-line data)
     :startColumn (:start-column data)
     :endLine (:end-line data)
     :endColumn (:end-column data)}))

(defn- source-position [data]
  (when (every? #(contains? data %) [:index :line :column])
    {:index (:index data)
     :line (:line data)
     :column (:column data)}))

(defn- normalize-diagnostic [phase error]
  (let [data (ex-data error)
        evaluation-form (:prose.alpha.evaluation/form data)
        region (case phase
                 :read data
                 :playground-evaluate (-> evaluation-form meta ::reader/parse-region)
                 nil)
        range-data (source-range region)
        position (cond
                   (= :read phase) (source-position data)
                   (and (= :companion-evaluate phase)
                        (number? (:line data))
                        (number? (:column data)))
                   {:line (:line data)
                    :column (:column data)})
        message (if (= :read phase)
                  (or (ex-message error) (.-message error) (str error))
                  (deepest-message error))]
    (cond-> {:phase (name phase)
             :source (if (= :companion-evaluate phase)
                       "Companion namespace"
                       "Playground source")
             :message message}
      range-data (assoc :range range-data)
      position (assoc :position position)
      (and (= :read phase) (:text data)) (assoc :failedText (:text data))
      (and (= :read phase) (:expected data)) (assoc :expected (:expected data)))))

(defn- render [{:keys [requestId program]}]
  (let [companion-source (get-in program [:companion :source])
        phase (atom (if companion-source :companion-evaluate :read))]
    (try
      (let [context (eval-sci/fork-sci-ctxt base-context)
            _ (when companion-source
                (sci/eval-string* context companion-source))
            _ (reset! phase :read)
            forms (reader/read-from-string (:source program))
            _ (reset! phase :playground-evaluate)
            evaluated (eval-sci/eval-forms-in-temp-ns context forms)
            _ (reset! phase :compile)
            generated-html (html/compile! evaluated)]
        {:type "rendered"
         :protocol protocol-version
         :requestId requestId
         :reader (pr-str forms)
         :evaluated (pr-str evaluated)
         :html generated-html})
      (catch :default error
        {:type "failed"
         :protocol protocol-version
         :requestId requestId
         :diagnostic (normalize-diagnostic @phase error)}))))

(defn- post! [message]
  (.postMessage js/self (clj->js message)))

(set! (.-onmessage js/self)
      (fn [event]
        (let [{:keys [type protocol] :as request}
              (js->clj (.-data event) :keywordize-keys true)]
          (when (and (= "render" type)
                     (= protocol-version protocol))
            (post! (render request))))))

(post! {:type "ready"
        :protocol protocol-version})
