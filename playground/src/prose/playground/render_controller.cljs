(ns prose.playground.render-controller
  (:require
   [prose.playground.protocol :as protocol]))

(def auto-delay 350)
(def execution-deadline 2000)

(def initialization-diagnostic
  {:message "The render worker could not initialize."
   :phase   "initialization"
   :source  nil})

(def timeout-diagnostic
  {:message "The Playground stopped execution after two seconds."
   :phase   "timeout"
   :source  nil})

(defn initial-state []
  {:diagnostic  nil
   :output      nil
   :renderState "waiting"
   :requestId   0
   :stale       false
   :workerState "initializing"})

(defn initial-runtime []
  {:active-request        nil
   :auto-timer           nil
   :deadline-timer       nil
   :last-successful-output nil
   :pending-request       nil
   :started?              false
   :state                 (initial-state)
   :worker                nil
   :worker-ready?         false})

(defn create-render-controller
  ([]
   (create-render-controller nil))
  ([options]
   (let [options (or options #js {})
         clear-timer (or (aget options "clearTimer") js/clearTimeout)
         create-worker (aget options "createWorker")
         on-change (or (aget options "onChange") (fn [_]))
         set-timer (or (aget options "setTimer") js/setTimeout)
         runtime_ (atom (initial-runtime))]
     (letfn [(publish! [patch]
               (swap! runtime_ update :state merge patch)
               (on-change (clj->js (:state @runtime_))))
             (clear-auto-timer! []
               (when-some [timer (:auto-timer @runtime_)]
                 (clear-timer timer))
               (swap! runtime_ assoc :auto-timer nil))
             (clear-deadline! []
               (when-some [timer (:deadline-timer @runtime_)]
                 (clear-timer timer))
               (swap! runtime_ assoc :deadline-timer nil))
             (terminate-worker! []
               (when-let [worker (:worker @runtime_)]
                 (.terminate worker))
               (swap! runtime_ assoc :worker nil :worker-ready? false))
             (fail! [diagnostic]
               (clear-deadline!)
               (swap! runtime_ assoc :active-request nil)
               (let [output (:last-successful-output @runtime_)]
                 (publish! {:diagnostic diagnostic
                            :output output
                            :renderState "failed"
                            :stale (some? output)})))
             (start-deadline! [request-id]
               (let [timer
                     (set-timer
                      (fn []
                        (when (= request-id
                                 (get-in @runtime_ [:active-request :id]))
                          (terminate-worker!)
                          (fail! timeout-diagnostic)
                          (spawn-worker!)))
                      execution-deadline)]
                 (swap! runtime_ assoc :deadline-timer timer)))
             (send-pending-request! []
               (when (and (:worker-ready? @runtime_)
                          (:pending-request @runtime_))
                 (let [request (:pending-request @runtime_)
                       worker (:worker @runtime_)]
                   (swap! runtime_
                          assoc
                          :active-request request
                          :pending-request nil)
                   (publish! {:diagnostic nil
                              :renderState "rendering"
                              :requestId (:id request)
                              :stale false})
                   (.postMessage worker
                                 (protocol/render-request
                                  (:id request)
                                  (:source request)
                                  (:companion-source request)))
                   (start-deadline! (:id request)))))
             (handle-message! [candidate message]
               (when (identical? candidate (:worker @runtime_))
                 (case (protocol/readiness-state message)
                   "ready"
                   (do
                     (swap! runtime_ assoc :worker-ready? true)
                     (publish! {:workerState "ready"})
                     (send-pending-request!))

                   "failed"
                   (do
                     (terminate-worker!)
                     (publish! {:workerState "failed"})
                     (fail! initialization-diagnostic))

                   (when-let [response
                              (protocol/current-render-response
                               message
                               (get-in @runtime_ [:state :requestId]))]
                     (when (= (aget response "requestId")
                              (get-in @runtime_ [:active-request :id]))
                       (clear-deadline!)
                       (swap! runtime_ assoc :active-request nil)
                       (if (= "rendered" (aget response "type"))
                         (let [output {:evaluated (aget response "evaluated")
                                       :html (aget response "html")
                                       :reader (aget response "reader")}]
                           (swap! runtime_ assoc :last-successful-output output)
                           (publish! {:diagnostic nil
                                      :output output
                                      :renderState "rendered"
                                      :stale false}))
                         (fail! (if-let [diagnostic (aget response "diagnostic")]
                                  (js->clj diagnostic :keywordize-keys true)
                                  {:message "Render failed."
                                   :phase "render"
                                   :source nil}))))))))
             (fail-initialization! []
               (terminate-worker!)
               (publish! {:workerState "failed"})
               (fail! initialization-diagnostic))
             (handle-worker-error! [candidate]
               (when (identical? candidate (:worker @runtime_))
                 (fail-initialization!)))
             (spawn-worker! []
               (swap! runtime_ assoc :worker-ready? false)
               (publish! {:workerState "initializing"})
               (try
                 (let [candidate (create-worker)]
                   (swap! runtime_ assoc :worker candidate)
                   (.addEventListener
                    candidate
                    "message"
                    (fn [event]
                      (handle-message! candidate (.-data event))))
                   (.addEventListener
                    candidate
                    "error"
                    (fn [_]
                      (handle-worker-error! candidate))))
                 (catch :default _
                   (fail-initialization!))))
             (render! [source companion-source]
               (clear-auto-timer!)
               (let [companion-source (when-not (undefined? companion-source)
                                        companion-source)
                     request {:companion-source companion-source
                              :id (inc (get-in @runtime_ [:state :requestId]))
                              :source source}]
                 (swap! runtime_ assoc :pending-request request)
                 (publish! {:diagnostic nil
                            :renderState "waiting"
                            :requestId (:id request)})
                 (cond
                   (:active-request @runtime_)
                   (do
                     (clear-deadline!)
                     (swap! runtime_ assoc :active-request nil)
                     (terminate-worker!)
                     (spawn-worker!))

                   (nil? (:worker @runtime_))
                   (spawn-worker!))
                 (send-pending-request!)
                 (:id request)))
             (schedule! [source companion-source]
               (clear-auto-timer!)
               (let [timer
                     (set-timer
                      (fn []
                        (swap! runtime_ assoc :auto-timer nil)
                        (render! source companion-source))
                      auto-delay)]
                 (swap! runtime_ assoc :auto-timer timer)))]
       (clj->js
        {:cancelScheduled clear-auto-timer!
         :getState (fn [] (clj->js (:state @runtime_)))
         :render render!
         :schedule schedule!
         :start (fn []
                  (when-not (:started? @runtime_)
                    (swap! runtime_ assoc :started? true)
                    (spawn-worker!)))
         :stop (fn []
                 (clear-auto-timer!)
                 (clear-deadline!)
                 (terminate-worker!))})))))
