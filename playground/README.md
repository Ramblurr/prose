# Prose Playground

The Playground is an isolated static application. Its ClojureScript and JavaScript dependencies do not enter the Prose library dependency graph.

Install the pinned JavaScript dependencies without running package scripts:

```sh
pnpm --dir playground install --frozen-lockfile --ignore-scripts
```

From the repository root, build and serve the artifact:

```sh
just playground-build
just playground-serve
```

The server listens on <http://localhost:8000>. The build does not install dependencies. Run `just playground-check` to execute the deterministic shell tests and a clean production build.
