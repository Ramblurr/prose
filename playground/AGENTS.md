# Playground Agent Instructions

## ClojureScript REPL with `brepl`

Install the pinned JavaScript dependencies first; the Node REPL resolves the
Playground's npm modules through `playground/node_modules`.

Start the isolated Piggieback-backed nREPL server from the repository root:

```sh
bb repl:playground
```

The recipe runs from `playground/`, so nREPL writes
`playground/.nrepl-port` without replacing the root JVM REPL's port file.
Run the remaining commands from `playground/` so `brepl` discovers that server:

```sh
cd playground

clone_response=$(brepl -m '{"op" "clone"}')
printf '%s\n' "$clone_response"
export CLJS_SESSION=$(
  printf '%s\n' "$clone_response" |
    sed -n 's/.*"new-session" "\([^"]*\)".*/\1/p'
)
test -n "$CLJS_SESSION"
```

A normal one-shot `brepl` evaluation does not preserve an nREPL session.
With `jq` available, define this shell helper to send each form through the
cloned session:

```sh
cljs_brepl() {
  code=$1
  encoded=$(printf '%s' "$code" | jq -Rs .)
  message=$(printf \
    '{"op" "eval" "session" "%s" "code" %s}' \
    "$CLJS_SESSION" "$encoded")
  brepl -m "$message"
}
```

Initialize one Node ClojureScript REPL in that session:

```sh
cljs_brepl '(do
  (require (quote [cljs.repl.node :as node])
           (quote [cider.piggieback :as piggie]))
  (piggie/cljs-repl
    (node/repl-env)
    :output-dir "../.cljs_node_repl/playground"))'
```

The output directory is deliberate. `playground/package.json` declares
`"type": "module"`; generating `.cljs_node_repl` beneath `playground/` makes
Node load `node_repl_deps.js` as ESM and ClojureScript's generated bridge fails
with `TypeError: ret.toString is not a function`. The root-level ignored
`.cljs_node_repl/playground` directory keeps those generated files in CommonJS
scope. Do not move it beneath `playground/`.

Evaluate ClojureScript and reload Playground namespaces through the same
session:

```sh
cljs_brepl '[(+ 20 22) js/process.version]'
cljs_brepl '(require (quote [prose.playground.host :as host]) :reload)'
cljs_brepl '[host/program-event host/render-state-event]'
```

Initialize only one Node REPL at a time. Before starting another, tear down the
current runtime and close its nREPL session:

```sh
cljs_brepl ':cljs/quit'
brepl -m "{\"op\" \"close\" \"session\" \"$CLJS_SESSION\"}"
```

Stop `bb repl:playground` with `Ctrl-C`.
