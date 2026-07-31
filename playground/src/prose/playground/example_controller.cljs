(ns prose.playground.example-controller)

(def persistence-key "prose-playground-authored")
(def persistence-version 1)

(defn create-example-controller [options]
  (let [examples (js->clj (aget options "examples") :keywordize-keys true)
        examples-by-id (into {} (map (juxt :id identity)) examples)
        default-example (first examples)
        on-activate (aget options "onActivate")
        storage (aget options "storage")
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
                             (clj->js
                              (assoc (select-keys @state_
                                                  [:companion :selectedExample :source])
                                     :version persistence-version)))))
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
              (on-activate (clj->js @state_))
              (clj->js @state_))]
      (clj->js
       {:editCompanion
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
          (clj->js @state_))

        :reset
        (fn []
          (activate! (canonical-state
                      (get examples-by-id (:selectedExample @state_)))))

        :select
        (fn [id]
          (activate! (canonical-state (get examples-by-id id))))

        :start
        (fn []
          (reset! state_ (restored-state))
          (on-activate (clj->js @state_))
          (clj->js @state_))}))))
