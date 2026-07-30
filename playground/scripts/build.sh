#!/usr/bin/env sh
set -eu

rm -rf dist target
mkdir -p dist/assets dist/examples/playground target/cljs
cp static/index.html dist/index.html
cp static/styles.css dist/assets/styles.css
cp \
  ../examples/01-text-and-code.prose \
  ../examples/02-semantic-html.prose \
  ../examples/03-custom-tag-function.prose \
  ../examples/04-html-from-a-collection.prose \
  dist/examples/
cp ../examples/playground/example_tags.clj dist/examples/playground/
pnpm exec esbuild src/host.js --bundle --format=esm --minify --outfile=dist/assets/host.js
clojure -M -m cljs.main \
  -O advanced \
  -t webworker \
  -co '{:browser-repl false :process-shim false :output-dir "target/cljs" :output-to "dist/assets/worker.js"}' \
  -c prose.playground.worker
