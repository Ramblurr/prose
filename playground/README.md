# Prose Playground

A playground to try out [Prose](../README.md).

Try it now at https://ramblurr.github.io/prose

The playground offers:

- Type `@` to insert a `◊`. Type `@@` to insert a literal `@`
- Several builtin examples showcasing different Prose features
- View the intermedisate oututs from the Reader and Evaluator
- Syntax highlighting for Prose and embedded Clojure
- Persistent code editor and theme

## Run the playground locally

From a local checkout of the repo:

```sh
bb playground:serve
```

Open <http://localhost:8000>

## Build the deployment artifact

Install the pinned JavaScript dependencies once, without running package scripts:

```sh
pnpm --dir playground install --frozen-lockfile --ignore-scripts
```

From the repository root, create a clean production artifact:

```sh
bb playground:build
```

The complete deployable site is `playground/dist/`. You can serve it from a domain root or any URL prefix.

## Test

```sh
bb playground:check
```

## Deploy to a static host

Copy the contents of `playground/dist/` to the directory served by your static host:

```sh
rsync -av --delete playground/dist/ user@example.org:/srv/www/playground/
```

## License

Copyright © 2026 Casey Link <casey@outskirtslabs.com>

Distributed under the Eclipse Public License v 2.0.

