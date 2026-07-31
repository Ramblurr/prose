(ns prose.playground.host
  (:require
   ["@codemirror/commands" :refer [defaultKeymap history historyKeymap]
    :rename {defaultKeymap default-keymap
             historyKeymap history-keymap}]
   ["@codemirror/view" :refer [EditorView keymap lineNumbers]
    :rename {lineNumbers line-numbers}]
   [clojure.string :as str]
   [prose.playground.example-controller :as examples]
   [prose.playground.lozenge-shorthand :as shorthand]
   [prose.playground.preview-document :as preview-document]
   [prose.playground.prose-language :as prose-language]
   [prose.playground.render-controller :as render]))

(def program-event "prose-playground-program")
(def render-state-event "prose-playground-render-state")
(def state-event "prose-playground-state")

(defn preview-projection [html]
  (let [^js template (.createElement js/document "template")
        ^js content (.-content template)]
    (set! (.-innerHTML template) html)
    (doseq [^js link (.querySelectorAll content "a, area")]
      (.removeAttribute link "href")
      (.removeAttribute link "xlink:href"))
    (doseq [^js refresh (.querySelectorAll content "meta[http-equiv]")]
      (when (= "refresh" (.toLowerCase (.-httpEquiv refresh)))
        (.remove refresh)))
    (.-innerHTML template)))

(defn rendered-preview-document [html appearance theme-enabled]
  (preview-document/preview-document
   (preview-projection (or html ""))
   #js {:appearance appearance
        :themeEnabled theme-enabled}))

(defn diagnostic-detail [^js diagnostic]
  (if-not diagnostic
    ""
    (let [source (.-source diagnostic)
          ^js position (.-position diagnostic)
          ^js range (.-range diagnostic)
          failed-text (.-failedText diagnostic)
          expected (.-expected diagnostic)
          details (cond-> []
                    source
                    (conj (str "Source: " source))

                    position
                    (conj (str "line "
                               (.-line position)
                               ", column "
                               (.-column position)))

                    range
                    (conj (str "range "
                               (.-startLine range)
                               ":"
                               (.-startColumn range)
                               "–"
                               (.-endLine range)
                               ":"
                               (.-endColumn range))
                          (str "indexes "
                               (.-startIndex range)
                               "–"
                               (.-endIndex range)))

                    failed-text
                    (conj (str "failed text: "
                               (js/JSON.stringify failed-text)))

                    expected
                    (conj (str "expected " expected)))]
      (str/join " · " details))))

(defn create-host-actions
  [{:keys [cancel-scheduled preview-document render reset-example schedule-current
           select-example]}]
  #js {:cancelScheduled cancel-scheduled
       :previewDocument preview-document
       :render render
       :resetExample reset-example
       :schedule (fn []
                   (schedule-current))
       :selectExample select-example})

(defn- dispatch! [event detail]
  (.dispatchEvent
   js/window
   (js/CustomEvent. event #js {:detail detail})))

(defn- publish! [detail]
  (dispatch! state-event detail))

(defn- show-controller-state! [^js state]
  (dispatch!
   render-state-event
   (js/Object.assign
    #js {}
    state
    #js {:diagnosticDetail (diagnostic-detail (.-diagnostic state))})))

(defn- browser-storage []
  (try
    (.-localStorage js/window)
    (catch :default _
      nil)))

(defn- example-text [url]
  (-> (js/fetch url)
      (.then (fn [^js response]
               (if (.-ok response)
                 (.text response)
                 (throw
                  (js/Error.
                   (str "Example request failed with HTTP "
                        (.-status response)
                        "."))))))))

(defn- replace-document! [^js editor source]
  (.dispatch
   editor
   #js {:changes #js {:from 0
                      :insert source
                      :to (.-length (.. editor -state -doc))}}))

(defn- example-descriptors [^js example-select host-url]
  (let [example-urls {"custom-tag-function"
                      {:companion (js/URL. "../examples/playground/example_tags.clj" host-url)
                       :source (js/URL. "../examples/03-custom-tag-function.prose" host-url)}

                      "html-from-a-collection"
                      {:source (js/URL. "../examples/04-html-from-a-collection.prose" host-url)}

                      "semantic-html"
                      {:source (js/URL. "../examples/02-semantic-html.prose" host-url)}

                      "text-and-code"
                      {:source (js/URL. "../examples/01-text-and-code.prose" host-url)}}]
    (to-array
     (map (fn [^js option]
            (let [{:keys [companion source]} (get example-urls (.-value option))]
              #js {:companion companion
                   :id (.-value option)
                   :source source
                   :title (.trim (.-textContent option))}))
          (.-options example-select)))))

(defn- current-program [runtime_]
  (let [^js example-controller (:example-controller @runtime_)
        ^js companion-editor (:companion-editor @runtime_)
        ^js source-editor (:source-editor @runtime_)
        ^js example-state (.getState example-controller)
        companion? (some? (.-companion example-state))]
    {:companion (when companion?
                  (.toString (.. companion-editor -state -doc)))
     :source (.toString (.. source-editor -state -doc))}))

(defn- create-editor
  [{:keys [label language on-edit parent request-render! shorthand? source]}]
  (let [extensions (concat
                    [(line-numbers)
                     (history)
                     language
                     prose-language/balanced-syntax]
                    (when shorthand? shorthand/at-shorthand)
                    [(.-lineWrapping ^js EditorView)
                     (.of ^js (.-contentAttributes ^js EditorView)
                          #js {:aria-labelledby label})
                     (.of ^js (.-updateListener ^js EditorView)
                          (fn [^js update]
                            (when (.-docChanged update)
                              (on-edit (.toString (.. update -state -doc)))
                              (dispatch! "prose-playground-edit" nil))))
                     (.of
                      ^js keymap
                      (to-array
                       (concat
                        [#js {:key "Mod-Enter"
                              :run (fn []
                                     (request-render!)
                                     true)}]
                        default-keymap
                        history-keymap)))])]
    (EditorView.
     #js {:doc source
          :extensions (to-array extensions)
          :parent parent})))

(defn start! []
  (let [runtime_ (atom {})
        ^js companion-editor-parent (.querySelector js/document "#companion-editor")
        ^js example-select (.querySelector js/document "#example-select")
        ^js source-editor-parent (.querySelector js/document "#source-editor")
        ^js host-script (.querySelector js/document "script[src$=\"/host.js\"]")
        host-url (.-src host-script)
        descriptors (example-descriptors example-select host-url)]
    (letfn [(render-program! [method source companion]
              (when-let [^js controller (:render-controller @runtime_)]
                (js-invoke controller method source companion)))
            (render-current-program! [method]
              (when (and (:source-editor @runtime_)
                         (:companion-editor @runtime_))
                (let [{:keys [source companion]} (current-program runtime_)]
                  (render-program! method source companion))))
            (request-render! []
              (render-current-program! "render"))
            (create-editors! [^js program]
              (let [source-editor
                    (create-editor
                     {:label "source-editor-label"
                      :language prose-language/prose-language
                      :on-edit (fn [source]
                                 (when-let [^js example-controller
                                            (:example-controller @runtime_)]
                                   (.editSource example-controller source)))
                      :parent source-editor-parent
                      :request-render! request-render!
                      :source (.-source program)
                      :shorthand? true})
                    companion-editor
                    (create-editor
                     {:label "companion-editor-label"
                      :language prose-language/clojure-language
                      :on-edit (fn [source]
                                 (when-let [^js example-controller
                                            (:example-controller @runtime_)]
                                   (.editCompanion example-controller source)))
                      :parent companion-editor-parent
                      :request-render! request-render!
                      :source (or (.-companion program) "")})]
                (swap! runtime_ assoc
                       :source-editor source-editor
                       :companion-editor companion-editor)
                (publish! #js {:editorReady true})))
            (activate-example! [^js program]
              (dispatch! program-event program)
              (if-let [source-editor (:source-editor @runtime_)]
                (do
                  (replace-document! source-editor (.-source program))
                  (replace-document! (:companion-editor @runtime_)
                                     (or (.-companion program) "")))
                (create-editors! program))
              (request-render!))
            (load-examples! []
              (-> (js/Promise.all
                   (.map
                    descriptors
                    (fn [^js descriptor]
                      (-> (js/Promise.all
                           #js [(example-text (.-source descriptor))
                                (if-let [companion (.-companion descriptor)]
                                  (example-text companion)
                                  (js/Promise.resolve nil))])
                          (.then
                           (fn [^js sources]
                             (js/Object.assign
                              #js {}
                              descriptor
                              #js {:source (aget sources 0)
                                   :companion (aget sources 1)})))))))
                  (.then
                   (fn [loaded-examples]
                     (let [^js example-controller
                           (examples/create-example-controller
                            #js {:examples loaded-examples
                                 :onActivate activate-example!
                                 :storage (browser-storage)})]
                       (swap! runtime_ assoc :example-controller example-controller)
                       (.start example-controller)
                       (publish! #js {:examplesReady true}))))
                  (.catch
                   (fn [^js error]
                     (publish!
                      #js {:diagnosticDetail ""
                           :diagnosticMessage (.-message error)
                           :diagnosticPhase "Initialization"
                           :emptyResult true
                           :renderStatus "Render failed"
                           :workerStatus "Initialization failed"})))))]
      (let [^js render-controller
            (render/create-render-controller
             #js {:createWorker (fn []
                                  (js/Worker.
                                   (js/URL. "./worker.js" host-url)))
                  :onChange show-controller-state!})]
        (swap! runtime_ assoc :render-controller render-controller)
        (set!
         (.-prosePlayground ^js js/globalThis)
         (create-host-actions
          {:cancel-scheduled (fn []
                               (.cancelScheduled render-controller))
           :preview-document rendered-preview-document
           :render request-render!
           :reset-example (fn []
                            (when-let [^js example-controller
                                       (:example-controller @runtime_)]
                              (.reset example-controller)))
           :schedule-current (fn []
                               (render-current-program! "schedule"))
           :select-example (fn [id]
                             (when-let [^js example-controller
                                        (:example-controller @runtime_)]
                               (.select example-controller id)))}))

        (.start render-controller)
        (load-examples!)))))

(when (exists? js/document)
  (.addEventListener js/document
                     "datastar-ready"
                     start!
                     #js {:once true}))
