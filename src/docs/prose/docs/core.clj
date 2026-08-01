(ns prose.docs.core
  (:require
   [prose.docs.evaluation :as ev]))

(def design-docs
  {"prose/docs/reader.md.prose"      "doc/reader.md"
   "prose/docs/evaluation.md.prose"  "doc/evaluation.md"
   "prose/docs/compilation.md.prose" "doc/compilation.md"})

(defn documents [input-map]
  (into {"README.md" (ev/document "prose/docs/README.md.prose" input-map)}
        (map (fn [[source-path target-path]]
               [target-path (ev/document source-path)]))
        design-docs))

(defn generate! [input-map]
  (doseq [[path content] (documents input-map)]
    (spit path content)))
