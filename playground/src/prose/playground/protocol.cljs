(ns prose.playground.protocol)

(def protocol-version 1)

(defn readiness-state [^js message]
  (when (= "ready" (when message (.-type message)))
    (if (= protocol-version (.-protocol message))
      "ready"
      "failed")))

(defn render-request
  ([request-id source]
   (render-request request-id source nil))
  ([request-id source companion-source]
   #js {:type "render"
        :protocol protocol-version
        :requestId request-id
        :program #js {:source source
                      :companion (when (some? companion-source)
                                   #js {:source companion-source})}}))

(defn current-render-response [^js message request-id]
  (when (and message
             (= protocol-version (.-protocol message))
             (= request-id (.-requestId message))
             (#{"rendered" "failed"} (.-type message)))
    message))
