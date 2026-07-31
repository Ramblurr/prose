
nrepl_middleware := "[cider.piggieback/wrap-cljs-repl]"

repl:
	clojure -M:clj:cljs:dev:nrepl:piggie:test -m nrepl.cmdline --middleware "{{nrepl_middleware}}"

playground-repl:
	cd playground && NODE_PATH="$PWD/node_modules" clojure -M:repl

repl-build:
	clojure -M:clj:nrepl:build -m nrepl.cmdline

clj-test opts="":
	clojure -M:clj:cljs:test -m kaocha.runner unit-clj {{opts}}

cljs-test opts="":
	clojure -M:clj:cljs:test -m kaocha.runner unit-cljs {{opts}}

bb-compat-test:
	bb -Sdeps '{:deps {io.github.jerems/prose {:local/root "."}}}' script/check_babashka_compat.clj

playground-build:
	cd playground && ./scripts/build.sh

playground-serve:
	python3 -m http.server --directory playground/dist 8000

playground-check:
	mkdir -p playground/dist && printf stale > playground/dist/stale-output
	just playground-build
	node playground/scripts/check-artifact.mjs
	cd playground && pnpm run check

test: clj-test cljs-test bb-compat-test playground-check
