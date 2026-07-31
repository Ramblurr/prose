(ns prose.playground.example-controller)

(def persistence-key "prose-playground-authored")
(def persistence-version 1)

(defn- state->js [{:keys [companion selectedExample source title]}]
  #js {:companion companion
       :selectedExample selectedExample
       :source source
       :title title})

(defn create-example-controller [^js options]
  (let [examples (js->clj (.-examples options) :keywordize-keys true)
        examples-by-id (into {} (map (juxt :id identity)) examples)
        default-example (first examples)
        on-activate (.-onActivate options)
        ^js storage (.-storage options)
        state_ (atom nil)
        canonical-state (fn [{:keys [companion id source title]}]
                          {:companion companion
                           :selectedExample id
                           :source source
                           :title title})]
    (letfn [(persist! []
              (try
                (when storage
                  (.setItem storage
                            persistence-key
                            (js/JSON.stringify
                             #js {:companion (:companion @state_)
                                  :selectedExample (:selectedExample @state_)
                                  :source (:source @state_)
                                  :version persistence-version})))
                (catch :default _)))
            (restored-state []
              (try
                (let [record (js->clj
                              (js/JSON.parse
                               (when storage (.getItem storage persistence-key)))
                              :keywordize-keys true)
                      example (get examples-by-id (:selectedExample record))]
                  (if (and (= persistence-version (:version record))
                           (string? (:source record))
                           example)
                    (let [canonical (canonical-state example)]
                      (cond
                        (nil? (:companion canonical))
                        (assoc canonical :source (:source record))

                        (nil? (:companion record))
                        (assoc canonical :source (:source record))

                        (string? (:companion record))
                        (assoc canonical
                               :companion (:companion record)
                               :source (:source record))

                        :else
                        (canonical-state default-example)))
                    (canonical-state default-example)))
                (catch :default _
                  (canonical-state default-example))))
            (activate! [next-state]
              (reset! state_ next-state)
              (persist!)
              (let [state (state->js @state_)]
                (on-activate state)
                state))]
      #js {:editCompanion
           (fn [companion]
             (when (some? (:companion @state_))
               (swap! state_ assoc :companion companion)
               (persist!)))

           :editSource
           (fn [source]
             (swap! state_ assoc :source source)
             (persist!))

           :getState
           (fn []
             (state->js @state_))

           :reset
           (fn []
             (activate! (canonical-state
                         (get examples-by-id (:selectedExample @state_)))))

           :select
           (fn [id]
             (activate!
              (assoc (canonical-state (get examples-by-id id))
                     :companion (:companion @state_))))

           :start
           (fn []
             (reset! state_ (restored-state))
             (let [state (state->js @state_)]
               (on-activate state)
               state))})))
