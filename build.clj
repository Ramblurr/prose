(ns build
  (:require
   [clojure.tools.build.api :as b]))

(defn jar [_]
  (let [class-dir "target/classes"]
    (b/delete {:path class-dir})
    (b/copy-dir {:src-dirs   ["src/main" "resources"]
                 :target-dir class-dir})
    (b/jar {:class-dir class-dir
            :jar-file  "target/prose.jar"})))
