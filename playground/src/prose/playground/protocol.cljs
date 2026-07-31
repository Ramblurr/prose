(ns prose.playground.protocol)

(def protocol-version 1)

(defn readiness-state [message]
  (when (= "ready" (when message (aget message "type")))
    (if (= protocol-version (aget message "protocol"))
      "ready"
      "failed")))

(defn render-request
  ([request-id source]
   (render-request request-id source nil))
  ([request-id source companion-source]
   (clj->js
    {:type      "render"
     :protocol  protocol-version
     :requestId request-id
     :program   {:source    source
                 :companion (when (some? companion-source)
                              {:source companion-source})}})))

(defn current-render-response [message request-id]
  (when (and message
             (= protocol-version (aget message "protocol"))
             (= request-id (aget message "requestId"))
             (#{"rendered" "failed"} (aget message "type")))
    message))
