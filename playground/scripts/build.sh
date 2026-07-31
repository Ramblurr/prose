#!/usr/bin/env sh
set -eu

mkdir -p target
exec 9>target/.playground.lock
flock 9

rm -rf dist target/host target/worker
mkdir -p dist/assets dist/examples/playground target/host target/worker
cp static/index.html dist/index.html
cp static/styles.css dist/assets/styles.css
cp vendor/datastar.js dist/assets/datastar.js
cp vendor/DATASTAR-LICENSE.md dist/assets/DATASTAR-LICENSE.md
cp vendor/ZPRINT-LICENSE.md dist/assets/ZPRINT-LICENSE.md
cp vendor/REWRITE-CLJ-LICENSE.md dist/assets/REWRITE-CLJ-LICENSE.md
cp \
  ../examples/01-text-and-code.prose \
  ../examples/02-semantic-html.prose \
  ../examples/03-custom-tag-function.prose \
  ../examples/04-html-from-a-collection.prose \
  dist/examples/
cp ../examples/playground/example_tags.clj dist/examples/playground/
clojure -M -m cljs.main \
  -O advanced \
  -t bundle \
  -co '{:browser-repl false :externs ["externs.js"] :process-shim false :output-dir "target/host" :output-to "target/host.js"}' \
  -c prose.playground.host
pnpm exec esbuild target/host.js \
  --bundle \
  --conditions=import \
  --format=esm \
  --minify \
  --outfile=dist/assets/host.js
clojure -M -m cljs.main \
  -O advanced \
  -t webworker \
  -co '{:browser-repl false :externs ["externs.js"] :process-shim false :output-dir "target/worker" :output-to "dist/assets/worker.js"}' \
  -c prose.playground.worker
node scripts/check-artifact.mjs
