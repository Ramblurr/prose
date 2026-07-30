(ns prose.playground.worker)

(def protocol-version 1)

(.postMessage js/self
              (clj->js {:type "ready"
                        :protocol protocol-version}))
