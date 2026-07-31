#!/usr/bin/env sh
set -eu

mkdir -p target
exec 9>target/.playground.lock
flock 9

rm -rf target/test
mkdir -p target/test/cljs
clojure -M:test -m cljs.main \
  -O advanced \
  -t bundle \
  -co '{:browser-repl false :externs ["externs.js"] :process-shim false :output-dir "target/test/cljs" :output-to "target/test/public.cjs"}' \
  -c prose.playground.test-exports
node --test test/*.test.js
