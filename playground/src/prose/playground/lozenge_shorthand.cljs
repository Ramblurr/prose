(ns prose.playground.lozenge-shorthand
  (:require
   ["@codemirror/state" :refer [StateEffect StateField]]
   ["@codemirror/view" :refer [EditorView]]))

(def ^js set-pending-pair
  (.define ^js StateEffect))

(def pending-pair
  (.define
   ^js StateField
   #js {:create (fn [] nil)
        :update (fn [_ ^js transaction]
                  (or (some (fn [^js effect]
                              (when (.is effect set-pending-pair)
                                (.-value effect)))
                            (.-effects transaction))
                      nil))}))

(defn shorthand-pending [^js state]
  (or (.field state pending-pair false) nil))

(defn pending-pair-collapsible? [^js state ^js pending]
  (let [^js selection (.. state -selection -main)]
    (and pending
         (.-empty selection)
         (= (.-head selection) (.-to pending))
         (= "◊" (.sliceDoc state
                           (.-from pending)
                           (.-to pending))))))

(defn at-input-transaction [^js state ^js intent]
  (when (and (= "insertText" (.-inputType intent))
             (= "@" (.-data intent))
             (not (.-isComposing intent))
             (not (.-compositionStarted intent)))
    (let [^js selection (.. state -selection -main)
          ^js pending (shorthand-pending state)]
      (if (pending-pair-collapsible? state pending)
        #js {:changes #js {:from (.-from pending)
                           :to (.-to pending)
                           :insert "@"}
             :selection #js {:anchor (inc (.-from pending))}
             :effects (.of set-pending-pair nil)
             :userEvent "input.type"
             :scrollIntoView true}
        (let [from (.-from selection)]
          #js {:changes #js {:from from
                             :to (.-to selection)
                             :insert "◊"}
               :selection #js {:anchor (inc from)}
               :effects (.of set-pending-pair
                             #js {:from from :to (inc from)})
               :userEvent "input.type"
               :scrollIntoView true})))))

(defn clear-pending-transaction [^js state]
  (when (shorthand-pending state)
    #js {:effects (.of set-pending-pair nil)}))

(def at-shorthand
  #js [pending-pair
       (.domEventHandlers
        ^js EditorView
        #js {:beforeinput
             (fn [^js event ^js view]
               (if-let [transaction
                        (at-input-transaction
                         (.-state view)
                         #js {:data (.-data event)
                              :inputType (.-inputType event)
                              :isComposing (.-isComposing event)
                              :compositionStarted (.-compositionStarted view)})]
                 (do
                   (.dispatch view transaction)
                   true)
                 false))
             :blur
             (fn [_ ^js view]
               (when-let [transaction
                          (clear-pending-transaction (.-state view))]
                 (.dispatch view transaction))
               false)})])
