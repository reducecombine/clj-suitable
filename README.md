# suitable - Addon for Figwheel and Emacs CIDER to aid exploratory development in ClojureScript [![Clojars Project](https://img.shields.io/clojars/v/org.rksm/suitable.svg)](https://clojars.org/org.rksm/suitable)

This project is a code completion backend for interactive repls and editors that
use runtime introspection to provide "IntelliSense" support. This can be
extremely useful and productive if you're experimenting around with unknown
APIs.

For example you work with DOM objects but can't remember how to query for child
elements. Type `(.| js/document)` (with `|` marking the postion of your cursor)
and press TAB. Methods and properties of `js/document` will appear — including
`querySelector` and `querySelectorAll`.

Currently Emacs (via CIDER) and figwheel.main are supported. If you want support
for your favorite tool please let me know and I'll look into it (no promises,
though).

## Demo

The animation shows how various properties and methods of the native DOM can be
accessed (Tab is used to show completions for the expression at the cursor):

![](doc/2019_07_22_suitable-figwheel.gif)

## Setup

### figwheel.main with rebel-readline

Please note that you need to use
[rebel-readline](https://github.com/bhauman/rebel-readline) with figwheel for
that to work. Plain repls have no completion feature.

#### Tools CLI

First make sure that the [normal Tools CLI
setup](https://figwheel.org/#setting-up-a-build-with-tools-cli) works.

Then modify your `deps.edn` so that org.rksm/suitable and it's setup code are
included:

```clojure
:aliases {:suitable {:extra-deps {org.rksm/suitable {:mvn/version "0.2.5"}}
                     :main-opts ["-e" "(require,'suitable.hijack-rebel-readline-complete)"
                                 "-m" "figwheel.main"
                                 "--build" "dev" "--repl"]}}
```

Also add a preload to your `dev.cljs.edn`:

```clojure
{:main hello-world.core
 :preloads [suitable.js-introspection]}
```

Finally start a figwheel repl via `clj -A:suitable`.

#### leiningen

First make sure that the [normal leiningen setup](https://figwheel.org/#setting-up-a-build-with-leiningen) works.

Add `[org.rksm/suitable "0.2.5"]` to your dependencies vector.

Then you can start a repl with `lein trampoline run -m suitable.figwheel.main -- -b dev -r`

### Emacs CIDER

For usage with `cider-jack-in-cljs` add these two lines to your emacs config:

```lisp
(cider-add-to-alist 'cider-jack-in-cljs-dependencies "org.rksm/suitable" "0.2.5")
(add-to-list 'cider-jack-in-cljs-nrepl-middlewares "suitable.middleware/wrap-complete")
```

That's it, your normal completion (e.g. via company) should pick up the completions provided by suitable.

### Custom nREPL server

To load suitable into a custom server you can load it using this monstrosity:

```shell
clj -Sdeps '{:deps {cider/cider-nrepl {:mvn/version "0.21.1"} org.rksm/suitable {:mvn/version "0.2.5"} cider/piggieback {:mvn/version"0.4.1"}}}' -m nrepl.cmdline --middleware "[cider.nrepl/cider-middleware,cider.piggieback/wrap-cljs-repl,suitable.middleware/wrap-complete]"
```

Or from within Clojure:

```clojure
(ns my-own-nrepl-server
  (:require cider.nrepl
            cider.piggieback
            nrepl.server
            suitable.middleware))

(defn start-cljs-nrepl-server []
(let [middlewares (conj (map resolve cider.nrepl/cider-middleware)
                        #'cider.piggieback/wrap-cljs-repl
                        #'suitable.middleware/wrap-complete)
      handler (apply nrepl.server/default-handler middlewares)]
  (nrepl.server/start-server :handler handler))
```

## How does it work?

suitable uses the same input as the widely used
[compliment](https://github.com/alexander-yakushev/compliment). This means it
gets a prefix string and a context form from the tool it is connected to. For
example you type `(.l| js/console)` with "|" marking where your cursor is. The
input we get would then be: prefix = `.l` and context = `(__prefix__
js/console)`.

suitable recognizes various ways how CLJS can access properties and methods,
such as `.`, `..`, `doto`, and threading forms. Also direct global access is
supported such as `js/console.log`. suitable will then figure out the expression
that defines the "parent object" that the property / method we want to use
belongs to. For the example above it would be `js/console`. The system then uses
the [EcmaScript property descriptor API](https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Global_Objects/Object/defineProperty)
to enumerate the object members. Those are converted into completion candidates
and send back to the tooling.

## License

This project is [MIT licensed](LICENSE).
