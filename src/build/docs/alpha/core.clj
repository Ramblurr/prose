(ns docs.alpha.core
  (:require
   [docs.alpha.evaluation :as ev]))

(def design-docs
  {"prose/alpha/reader.md.prose"      "doc/reader.md"
   "prose/alpha/evaluation.md.prose"  "doc/evaluation.md"
   "prose/alpha/compilation.md.prose" "doc/compilation.md"})

(defn documents [input-map]
  (into {"README.md" (ev/document "README.md.prose" input-map)}
        (map (fn [[source-path target-path]]
               [target-path (ev/document source-path)]))
        design-docs))

(defn generate! [input-map]
  (doseq [[path content] (documents input-map)]
    (spit path content)))
