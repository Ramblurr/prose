(ns playground.example-tags
  (:require
   [clojure.string :as str]
   [fr.jeremyschoffen.prose.alpha.document.lib :as lib]))

(defn status-label [status]
  (let [label (name status)]
    (lib/xml-tag :mark
                 {:class "status-label"
                  :data-length (count label)}
                 (str/upper-case label))))
