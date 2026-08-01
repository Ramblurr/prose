(ns prose.build.docs
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as string]
   [docs.alpha.core :as docs]))

(def lib-name 'io.github.jerems/prose)

(defn- git-output! [& args]
  (let [{:keys [exit out] :as result} (apply shell/sh "git" args)]
    (if (zero? exit)
      (string/trim out)
      (throw (ex-info "Git command failed" result)))))

(defn latest-git-coord []
  (let [tag (git-output! "describe" "--tags" "--abbrev=0" "HEAD")
        sha (git-output! "rev-parse" "--short=10" (str tag "^{commit}"))]
    {lib-name {:git/tag tag
               :git/sha sha}}))

(defn- rendered-documents []
  (docs/documents {:git-coord (latest-git-coord)}))

(defn generate! [_]
  (docs/generate! {:git-coord (latest-git-coord)}))

(defn check! [_]
  (let [stale (->> (rendered-documents)
                   (keep (fn [[path content]]
                           (when (not= content (slurp path))
                             path)))
                   sort
                   vec)]
    (when (seq stale)
      (throw (ex-info (str "Stale generated documentation: "
                           (string/join ", " stale))
                      {:stale stale})))))

(comment
  (latest-git-coord)
  (check! {}))
