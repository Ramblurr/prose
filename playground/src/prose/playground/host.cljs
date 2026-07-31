(ns prose.playground.host
  (:require
   [goog.object :as gobj]
   [prose.playground.example-controller :as examples]
   [prose.playground.interop :as interop]
   [prose.playground.lozenge-shorthand :as shorthand]
   [prose.playground.preview-document :as preview-document]
   [prose.playground.prose-language :as prose-language]
   [prose.playground.render-controller :as render]))

(def commands-module (js/require "@codemirror/commands"))
(def view-module (js/require "@codemirror/view"))

(def default-keymap (gobj/get commands-module "defaultKeymap"))
(def history (gobj/get commands-module "history"))
(def history-keymap (gobj/get commands-module "historyKeymap"))
(def editor-view (gobj/get view-module "EditorView"))
(def keymap (gobj/get view-module "keymap"))
(def line-numbers (gobj/get view-module "lineNumbers"))

(def program-event "prose-playground-program")
(def render-state-event "prose-playground-render-state")
(def state-event "prose-playground-state")

(defn preview-projection [html]
  (let [template (.createElement js/document "template")]
    (gobj/set template "innerHTML" html)
    (doseq [link (array-seq
                  (.querySelectorAll (gobj/get template "content") "a, area"))]
      (.removeAttribute link "href")
      (.removeAttribute link "xlink:href"))
    (doseq [refresh (array-seq
                     (.querySelectorAll (gobj/get template "content")
                                        "meta[http-equiv]"))]
      (when (= "refresh" (.toLowerCase (gobj/get refresh "httpEquiv")))
        (.remove refresh)))
    (gobj/get template "innerHTML")))

(defn rendered-preview-document [html appearance theme-enabled]
  (preview-document/preview-document
   (preview-projection (or html ""))
   (clj->js {:appearance appearance
             :themeEnabled theme-enabled})))

(defn diagnostic-detail [diagnostic]
  (if-not diagnostic
    ""
    (let [details (array)
          source (gobj/get diagnostic "source")
          position (gobj/get diagnostic "position")
          range (gobj/get diagnostic "range")]
      (when source
        (.push details (str "Source: " source)))
      (when position
        (.push details
               (str "line "
                    (gobj/get position "line")
                    ", column "
                    (gobj/get position "column"))))
      (when range
        (.push details
               (str "range "
                    (gobj/get range "startLine")
                    ":"
                    (gobj/get range "startColumn")
                    "–"
                    (gobj/get range "endLine")
                    ":"
                    (gobj/get range "endColumn")))
        (.push details
               (str "indexes "
                    (gobj/get range "startIndex")
                    "–"
                    (gobj/get range "endIndex"))))
      (when-let [failed-text (gobj/get diagnostic "failedText")]
        (.push details
               (str "failed text: " (js/JSON.stringify failed-text))))
      (when-let [expected (gobj/get diagnostic "expected")]
        (.push details (str "expected " expected)))
      (.join details " · "))))

(defn start! []
  (let [runtime (js-obj)
        companion-editor-parent (.querySelector js/document "#companion-editor")
        example-select (.querySelector js/document "#example-select")
        source-editor-parent (.querySelector js/document "#source-editor")
        host-url (gobj/get
                  (.querySelector js/document "script[src$=\"/host.js\"]")
                  "src")
        example-urls
        {"custom-tag-function"
         {:companion (js/URL. "../examples/playground/example_tags.clj" host-url)
          :source (js/URL. "../examples/03-custom-tag-function.prose" host-url)}

         "html-from-a-collection"
         {:source (js/URL. "../examples/04-html-from-a-collection.prose" host-url)}

         "semantic-html"
         {:source (js/URL. "../examples/02-semantic-html.prose" host-url)}

         "text-and-code"
         {:source (js/URL. "../examples/01-text-and-code.prose" host-url)}}
        example-descriptors
        (to-array
         (map (fn [option]
                (clj->js
                 (assoc (get example-urls (gobj/get option "value"))
                        :id (gobj/get option "value")
                        :title (.trim (gobj/get option "textContent")))))
              (array-seq (gobj/get example-select "options"))))]
    (letfn [(dispatch! [event detail]
              (.dispatchEvent
               js/window
               (js/CustomEvent. event #js {:detail detail})))
            (publish! [detail]
              (dispatch! state-event detail))
            (show-controller-state! [state]
              (dispatch!
               render-state-event
               (js/Object.assign
                #js {}
                state
                #js {:diagnosticDetail
                     (diagnostic-detail (gobj/get state "diagnostic"))})))
            (current-program []
              (let [example-controller (gobj/get runtime "exampleController")
                    companion-editor (gobj/get runtime "companionEditor")
                    source-editor (gobj/get runtime "sourceEditor")
                    example-state (interop/call example-controller "getState")
                    companion? (some? (gobj/get example-state "companion"))]
                (clj->js
                 {:companion (when companion?
                               (.toString
                                (gobj/get (gobj/get companion-editor "state")
                                          "doc")))
                  :source (.toString
                           (gobj/get (gobj/get source-editor "state") "doc"))})))
            (render-program! [method source companion]
              (when-let [controller (gobj/get runtime "renderController")]
                (interop/call controller method source companion)))
            (request-render! []
              (when (and (gobj/get runtime "sourceEditor")
                         (gobj/get runtime "companionEditor"))
                (let [program (current-program)]
                  (render-program! "render"
                                   (gobj/get program "source")
                                   (gobj/get program "companion")))))
            (create-editor [options]
              (let [shorthand? (boolean (gobj/get options "shorthand"))
                    extensions
                    (concat
                     [(interop/invoke line-numbers)
                      (interop/invoke history)
                      (gobj/get options "language")
                      prose-language/balanced-syntax]
                     (when shorthand? (array-seq shorthand/at-shorthand))
                     [(gobj/get editor-view "lineWrapping")
                      (interop/call
                       (gobj/get editor-view "contentAttributes")
                       "of"
                       (clj->js
                        {:aria-labelledby (gobj/get options "label")}))
                      (interop/call
                       (gobj/get editor-view "updateListener")
                       "of"
                       (fn [update]
                         (when (gobj/get update "docChanged")
                           ((gobj/get options "onEdit")
                            (.toString
                             (gobj/get (gobj/get update "state") "doc")))
                           (dispatch! "prose-playground-edit"
                                      (current-program)))))
                      (interop/call
                       keymap
                       "of"
                       (to-array
                        (concat
                         [(clj->js
                           {:key "Mod-Enter"
                            :run (fn []
                                   (request-render!)
                                   true)})]
                         (array-seq default-keymap)
                         (array-seq history-keymap))))])]
                (js/Reflect.construct
                 editor-view
                 #js [(clj->js
                       {:doc (gobj/get options "source")
                        :extensions (to-array extensions)
                        :parent (gobj/get options "parent")})])))
            (create-editors! [program]
              (gobj/set
               runtime
               "sourceEditor"
               (create-editor
                (clj->js
                 {:label "source-editor-label"
                  :language prose-language/prose-language
                  :onEdit (fn [source]
                            (interop/call
                             (gobj/get runtime "exampleController")
                             "editSource"
                             source))
                  :parent source-editor-parent
                  :source (gobj/get program "source")
                  :shorthand true})))
              (gobj/set
               runtime
               "companionEditor"
               (create-editor
                (clj->js
                 {:label "companion-editor-label"
                  :language prose-language/clojure-language
                  :onEdit (fn [source]
                            (interop/call
                             (gobj/get runtime "exampleController")
                             "editCompanion"
                             source))
                  :parent companion-editor-parent
                  :source (or (gobj/get program "companion") "")})))
              (publish! #js {:editorReady true}))
            (replace-document! [editor source]
              (interop/call
               editor
               "dispatch"
               (clj->js
                {:changes {:from 0
                           :insert source
                           :to (gobj/get
                                (gobj/get (gobj/get editor "state") "doc")
                                "length")}})))
            (activate-example! [program]
              (dispatch! program-event program)
              (if-let [source-editor (gobj/get runtime "sourceEditor")]
                (do
                  (replace-document! source-editor
                                     (gobj/get program "source"))
                  (replace-document! (gobj/get runtime "companionEditor")
                                     (or (gobj/get program "companion") "")))
                (create-editors! program))
              (request-render!))
            (browser-storage []
              (try
                (.-localStorage js/window)
                (catch :default _
                  nil)))
            (example-text [url]
              (-> (js/fetch url)
                  (.then (fn [response]
                           (if (gobj/get response "ok")
                             (.text response)
                             (throw
                              (js/Error.
                               (str "Example request failed with HTTP "
                                    (gobj/get response "status")
                                    "."))))))))
            (load-examples! []
              (-> (js/Promise.all
                   (.map
                    example-descriptors
                    (fn [descriptor]
                      (-> (js/Promise.all
                           #js [(example-text (gobj/get descriptor "source"))
                                (if-let [companion
                                         (gobj/get descriptor "companion")]
                                  (example-text companion)
                                  (js/Promise.resolve nil))])
                          (.then
                           (fn [sources]
                             (js/Object.assign
                              #js {}
                              descriptor
                              #js {:source (aget sources 0)
                                   :companion (aget sources 1)})))))))
                  (.then
                   (fn [loaded-examples]
                     (let [example-controller
                           (examples/create-example-controller
                            (clj->js
                             {:examples loaded-examples
                              :onActivate activate-example!
                              :storage (browser-storage)}))]
                       (gobj/set runtime
                                 "exampleController"
                                 example-controller)
                       (interop/call example-controller "start")
                       (publish! #js {:examplesReady true}))))
                  (.catch
                   (fn [error]
                     (publish!
                      (clj->js
                       {:diagnosticDetail ""
                        :diagnosticMessage (gobj/get error "message")
                        :diagnosticPhase "Initialization"
                        :emptyResult true
                        :renderStatus "Render failed"
                        :workerStatus "Initialization failed"}))))))]
      (let [render-controller
            (render/create-render-controller
             (clj->js
              {:createWorker (fn []
                               (js/Worker.
                                (js/URL. "./worker.js" host-url)))
               :onChange show-controller-state!}))]
        (gobj/set runtime "renderController" render-controller)
        (gobj/set
         js/globalThis
         "prosePlayground"
         (clj->js
          {:cancelScheduled
           (fn []
             (interop/call render-controller "cancelScheduled"))
           :previewDocument rendered-preview-document
           :render request-render!
           :schedule (fn [source companion]
                       (render-program! "schedule" source companion))
           :resetExample
           (fn []
             (when-let [example-controller
                        (gobj/get runtime "exampleController")]
               (interop/call example-controller "reset")))
           :selectExample
           (fn [id]
             (when-let [example-controller
                        (gobj/get runtime "exampleController")]
               (interop/call example-controller "select" id)))}))

        (interop/call render-controller "start")
        (load-examples!)))))

(when (exists? js/document)
  (.addEventListener js/document
                     "datastar-ready"
                     start!
                     #js {:once true}))
