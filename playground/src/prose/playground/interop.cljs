(ns prose.playground.interop
  (:require
   [goog.object :as gobj]))

(defn call
  ([object method]
   (.call (gobj/get object method) object))
  ([object method argument]
   (.call (gobj/get object method) object argument))
  ([object method argument-1 argument-2]
   (.call (gobj/get object method) object argument-1 argument-2)))

(defn invoke
  ([f]
   (.call f nil))
  ([f argument]
   (.call f nil argument)))
