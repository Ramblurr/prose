(ns prose.playground.lozenge-shorthand
  (:require
   [goog.object :as gobj]
   [prose.playground.interop :as interop]))

(def state-module (js/require "@codemirror/state"))
(def view-module (js/require "@codemirror/view"))

(def state-effect (gobj/get state-module "StateEffect"))
(def state-field (gobj/get state-module "StateField"))
(def editor-view (gobj/get view-module "EditorView"))

(def set-pending-pair
  (interop/call state-effect "define"))

(def pending-pair
  (interop/call
   state-field
   "define"
   (clj->js
    {:create (fn [] nil)
     :update (fn [_ transaction]
               (or (some (fn [effect]
                           (when (interop/call effect "is" set-pending-pair)
                             (gobj/get effect "value")))
                         (array-seq (gobj/get transaction "effects")))
                   nil))})))

(defn shorthand-pending [state]
  (or (interop/call state "field" pending-pair false) nil))

(defn pending-pair-collapsible? [state pending]
  (let [selection (gobj/get (gobj/get state "selection") "main")]
    (and pending
         (gobj/get selection "empty")
         (= (gobj/get selection "head") (gobj/get pending "to"))
         (= "◊" (interop/call state
                              "sliceDoc"
                              (gobj/get pending "from")
                              (gobj/get pending "to"))))))

(defn at-input-transaction [state intent]
  (when (and (= "insertText" (gobj/get intent "inputType"))
             (= "@" (gobj/get intent "data"))
             (not (gobj/get intent "isComposing"))
             (not (gobj/get intent "compositionStarted")))
    (let [selection (gobj/get (gobj/get state "selection") "main")
          pending (shorthand-pending state)]
      (if (pending-pair-collapsible? state pending)
        (clj->js
         {:changes {:from (gobj/get pending "from")
                    :to (gobj/get pending "to")
                    :insert "@"}
          :selection {:anchor (inc (gobj/get pending "from"))}
          :effects (interop/call set-pending-pair "of" nil)
          :userEvent "input.type"
          :scrollIntoView true})
        (let [from (gobj/get selection "from")]
          (clj->js
           {:changes {:from from
                      :to (gobj/get selection "to")
                      :insert "◊"}
            :selection {:anchor (inc from)}
            :effects (interop/call set-pending-pair
                                   "of"
                                   (clj->js {:from from :to (inc from)}))
            :userEvent "input.type"
            :scrollIntoView true}))))))

(defn clear-pending-transaction [state]
  (when (shorthand-pending state)
    (clj->js {:effects (interop/call set-pending-pair "of" nil)})))

(def at-shorthand
  #js [pending-pair
       (interop/call
        editor-view
        "domEventHandlers"
        (clj->js
         {:beforeinput
          (fn [event view]
            (if-let [transaction
                     (at-input-transaction
                      (gobj/get view "state")
                      (clj->js
                       {:data (gobj/get event "data")
                        :inputType (gobj/get event "inputType")
                        :isComposing (gobj/get event "isComposing")
                        :compositionStarted (gobj/get view "compositionStarted")}))]
              (do
                (interop/call view "dispatch" transaction)
                true)
              false))
          :blur
          (fn [_ view]
            (when-let [transaction
                       (clear-pending-transaction (gobj/get view "state"))]
              (interop/call view "dispatch" transaction))
            false)}))])
