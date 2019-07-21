(ns runtime-completion.js-completions
  (:require [clojure.pprint :refer [cl-format]]
            [clojure.spec.alpha :as s]
            [clojure.string :refer [replace split starts-with?]]
            [clojure.zip :as zip]
            [runtime-completion.ast :refer [tree-zipper]]
            [runtime-completion.spec :as spec]))

(def ^:private obj-expr-eval-template "(do
  (require 'runtime-completion.core)
  (runtime-completion.core/property-names-and-types ~A ~S))")

(defn- js-properties-of-object
  "Returns the properties of the object we get by evaluating `obj-expr` filtered
  by all those that start with `prefix`."
  ([cljs-eval-fn ns obj-expr]
   (js-properties-of-object cljs-eval-fn ns obj-expr nil))
  ([cljs-eval-fn ns obj-expr prefix]
   {:pre [(s/valid? ::spec/non-empty-string obj-expr)
          (s/valid? (s/nilable string?) prefix)]
    :post [(s/valid? (s/keys :error (s/nilable string?)
                             :value (s/coll-of (s/keys {:name ::spec/non-empty-string
                                                        :hierarchy int?
                                                        :type ::spec/non-empty-string}))) %)]}
   (let [code (cl-format nil obj-expr-eval-template obj-expr prefix)]
     (cljs-eval-fn ns code))))

(defn find-prefix [form]
  "Tree search for the symbol '__prefix. Returns a zipper."
  (loop [node (tree-zipper form)]
    (if (= '__prefix__ (zip/node node))
      node
      (when-not (zip/end? node)
        (recur (zip/next node))))))

(defn thread-form?
  "True if form looks like the name of a thread macro."
  [form]
  (->> form
       str
       (re-find #"->")
       nil?
       not))

(defn doto-form? [form]
  (= form 'doto))

(defn expr-for-parent-obj
  "Given the context and symbol of a completion request, will try to find an
  expression that evaluates to the object being accessed."
  [symbol context]
  (when-let [form (try
                    (with-in-str context (read *in* nil nil))
                    (catch Exception e
                      (cl-format *err* "error while gathering cljs runtime completions: ~S~%" e)
                      nil))]
    (let [prefix (find-prefix form)
          left-sibling (zip/left prefix)
          first? (nil? left-sibling)
          first-sibling (and (not first?) (some-> prefix zip/leftmost zip/node))
          first-sibling-in-parent (some-> prefix zip/up zip/leftmost zip/node)
          threaded? (if first? (thread-form? first-sibling-in-parent) (thread-form? first-sibling) )
          doto? (if first? (doto-form? first-sibling-in-parent) (doto-form? first-sibling))
          dot-fn? (starts-with? symbol ".")]

      (letfn [(with-type [type maybe-expr]
                (when maybe-expr
                  {:type type
                   :obj-expr maybe-expr}))]
       (cond
         ;; is it a threading macro?
         threaded?
         (with-type :-> (if first?
                          ;; parent is the thread
                          (-> prefix zip/up zip/lefts str)
                          ;; thread on same level
                          (-> prefix zip/lefts str)))

         doto?
         (with-type :doto (if first?
                            ;; parent is the thread
                            (-> prefix zip/up zip/leftmost zip/right zip/node str)
                            ;; thread on same level
                            (-> prefix zip/leftmost zip/right zip/node str)))

         ;; a .. form: if __prefix__ is a prop deeper than one level we need the ..
         ;; expr up to that point. If just the object that is accessed is left of
         ;; prefix, we can take that verbatim.
         ;; (.. js/console log) => js/console
         ;; (.. js/console -memory -jsHeapSizeLimit) => (.. js/console -memory)
         (and first-sibling (#{"." ".."} (str first-sibling)) left-sibling)
         (with-type :.. (let [lefts (-> prefix zip/lefts)]
                          (if (<= (count lefts) 2)
                            (str (last lefts))
                            (str lefts))))

         ;; (.. js/window -console (log "foo")) => (.. js/window -console)
         (and first? (-> prefix zip/up zip/leftmost zip/node str (= "..")))
         (with-type :.. (let [lefts (-> prefix zip/up zip/lefts)]
                          (if (<= (count lefts) 2)
                            (str (last lefts))
                            (str lefts))))

         ;; simple (.foo bar)
         (and first? dot-fn?)
         (with-type :. (some-> prefix zip/right zip/node str)))))))

(def global-expr-re #"^js/((?:[^\.]+\.)*)([^\.]*)$")
(def dot-dash-prefix-re #"^\.-?")
(def dash-prefix-re #"^-")
(def dot-prefix-re #"\.")

(defn analyze-symbol-and-context
  "Build a configuration that we can use to fetch the properties from an object
  that is the result of some `obj-expr` when evaled and that is used to convert
  those properties into candidates for completion."
  [symbol context]
  (if (starts-with? symbol "js/")

    ;; symbol is a global like js/console or global/property like js/console.log
    (let [[_ dotted-obj-expr prefix] (re-matches global-expr-re symbol)
          obj-expr-parts (keep not-empty (split dotted-obj-expr dot-prefix-re))
          ;; builds an expr like
          ;; "(this-as this (.. this -window))" for symbol = "js/window.console"
          ;; or "(this-as this this)" for symbol = "js/window"
          obj-expr (cl-format nil "(this-as this ~[this~:;(.. this ~{-~A~^ ~})~])"
                              (count obj-expr-parts) obj-expr-parts)]
      obj-expr-parts
      {:prefix prefix
       :prepend-to-candidate (str "js/" dotted-obj-expr)
       :vars-have-dashes? false
       :obj-expr obj-expr
       :type :global})

    ;; symbol is just a property name embedded in some expr
    (let [{:keys [type] :as expr-and-type} (expr-for-parent-obj symbol context)]
      (assoc expr-and-type
             :prepend-to-candidate (if (starts-with? symbol ".") "." "")
             :prefix (case type
                       :.. (replace symbol dash-prefix-re "")
                       (replace symbol dot-dash-prefix-re ""))
             :vars-have-dashes? true))))

(defn handle-completion-msg
  "Given some context (the toplevel form that has changed) and a symbol string
  that represents the last typed input, we try to find out if the context/symbol
  are object access (property access or method call). If so, we try to extract a
  form that we can evaluate to get the object that is accessed. If we get the
  object, we enumerate it's properties and methods and generate a list of
  matching completions for those."
  [cljs-eval-fn {:keys [ns symbol extra-metadata]} context]
  {:pre [(s/valid? ::spec/non-empty-string symbol)
         (s/valid? string? context)]
   :post [(s/valid? (s/nilable ::spec/completions) %)]}

  (let [{:keys [prefix prepend-to-candidate vars-have-dashes? obj-expr type]}
        (analyze-symbol-and-context symbol context)
        global? (#{:global} type)]
    (when-let [{error :error properties :value} (and obj-expr (js-properties-of-object cljs-eval-fn ns obj-expr prefix))]
      (for [{:keys [name type]} properties
            :let [maybe-dash (if (and vars-have-dashes? (= "var" type)) "-" "")
                  candidate (str prepend-to-candidate maybe-dash name)]
            :when (starts-with? candidate symbol)]
        (do
          {:type type :candidate candidate :ns (if global? "js" obj-expr)})))))