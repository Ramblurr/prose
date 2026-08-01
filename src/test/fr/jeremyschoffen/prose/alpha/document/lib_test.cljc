(ns fr.jeremyschoffen.prose.alpha.document.lib-test
  (:require
    #?(:clj [clojure.test :refer [deftest are]]
       :cljs [cljs.test :refer-macros [deftest are]])
    [fr.jeremyschoffen.prose.alpha.document.lib :as lib :include-macros true]))

#_{:clj-kondo/ignore [:uninitialized-var]}
(lib/def-xml-tag div)
(lib/def-xml-tag link :link)

(def container (lib/make-mixed-in div #{:container}))
(def container-grid (lib/make-mixed-in container ["grid"]))

(deftest recognizes-tags
  (are [expected value] (= expected (lib/tag? value))
    false {}
    false {:type "text/css"}
    false {:tag :link}
    true {:tag :link :type :tag}))

(deftest constructs-tags
  (are [expected actual] (= expected actual)
    {:tag :div :type :tag}
    (div)

    {:tag :div :attrs {} :type :tag}
    (div {})

    {:tag :div :content ["content"] :type :tag}
    (div "content")

    {:tag :div :attrs {} :content ["content"] :type :tag}
    (div {} "content")

    {:tag :div
     :content [{:tag :div :type :tag}
               {:tag :div :type :tag}
               {:tag :div :type :tag}]
     :type :tag}
    (div (div) (div) (div))

    {:tag :div :attrs {:class "toto"} :content ["content"] :type :tag}
    (div {:class "toto"} "content")

    {:tag :div :attrs {:class "toto"} :type :tag}
    (div {:class "toto"})

    {:tag :link
     :attrs {:rel "stylesheet" :type "text/css" :href "some/path"}
     :type :tag}
    (link {:rel "stylesheet" :type "text/css" :href "some/path"})

    {:tag :link :attrs {:type "text/css"} :type :tag}
    (lib/xml-tag :link {:type "text/css"})))

(deftest converts-class-attributes
  (are [expected actual] (= expected actual)
    #{} (lib/attr->set "")
    #{"grid" "col-2" "container"} (lib/attr->set "container grid col-2")
    #{"grid" "col-2" "container"} (-> "container grid col-2"
                                         lib/attr->set
                                         lib/set->attr
                                         lib/attr->set)))

(deftest mixes-in-classes
  (are [expected actual] (= expected actual)
    {:tag :div
     :attrs {:class #{"toto" "container"}}
     :content ["contained"]
     :type :tag}
    (update-in (container {:class "toto"} "contained")
               [:attrs :class]
               lib/attr->set)

    {:tag :div
     :attrs {:class #{"grid" "titi" "container"}}
     :content ["contaited 2"]
     :type :tag}
    (update-in (container-grid {:class "titi"} "contaited 2")
               [:attrs :class]
               lib/attr->set)

    {::lib/added-classes #{:container}
     ::lib/original-cstr div}
    (meta container)

    {::lib/added-classes #{"grid" :container}
     ::lib/original-cstr div}
    (meta container-grid)))

