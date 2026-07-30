(ns prose.playground.worker
  (:require
    [clojure.string]
    [fr.jeremyschoffen.prose.alpha.document.sci :as document :include-macros true]
    [fr.jeremyschoffen.prose.alpha.eval.sci :as eval-sci]
    [fr.jeremyschoffen.prose.alpha.out.html.compiler :as html]
    [fr.jeremyschoffen.prose.alpha.out.html.tags]
    [fr.jeremyschoffen.prose.alpha.reader.core :as reader]))

(def ^:private protocol-version 1)

(def ^:private base-context
  (document/init
    {:namespaces
     (document/make-ns-bindings
       clojure.string
       fr.jeremyschoffen.prose.alpha.out.html.tags)}))

(defn- deepest-message [error]
  (loop [current error
         message (or (ex-message error) (.-message error) (str error))]
    (if-let [cause (ex-cause current)]
      (recur cause (or (ex-message cause) message))
      message)))

(defn- render [{:keys [requestId program]}]
  (let [phase (atom :read)]
    (try
      (let [forms (reader/read-from-string (:source program))
            _ (reset! phase :playground-evaluate)
            evaluated (eval-sci/eval-forms-in-temp-ns
                        (eval-sci/fork-sci-ctxt base-context)
                        forms)
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
         :diagnostic {:phase (name @phase)
                      :message (deepest-message error)}}))))

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
