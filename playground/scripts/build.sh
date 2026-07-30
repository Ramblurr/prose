#!/usr/bin/env sh
set -eu

rm -rf dist target
mkdir -p dist/assets dist/examples target/cljs
cp static/index.html dist/index.html
cp static/styles.css dist/assets/styles.css
cp ../examples/01-text-and-code.prose dist/examples/01-text-and-code.prose
pnpm exec esbuild src/host.js --bundle --format=esm --minify --outfile=dist/assets/host.js
clojure -M -m cljs.main \
  -O advanced \
  -t webworker \
  -co '{:browser-repl false :process-shim false :output-dir "target/cljs" :output-to "dist/assets/worker.js"}' \
  -c prose.playground.worker
