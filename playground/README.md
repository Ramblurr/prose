# Prose Playground

The Playground is an isolated static application. Its ClojureScript, npm, and vendored browser dependencies do not enter the Prose library dependency graph.

Install the pinned JavaScript dependencies without running package scripts:

```sh
pnpm --dir playground install --frozen-lockfile --ignore-scripts
```

Datastar v1.0.2 is vendored as its upstream browser bundle under `vendor/`; see `vendor/README.md` for provenance, license, and the machine-checked digest. The build copies both the bundle and license into the relocatable artifact without fetching them.

From the repository root, build and serve the artifact:

```sh
just playground-build
just playground-serve
```

The server listens on <http://localhost:8000>. The build does not install dependencies. Run `just playground-check` to compile and execute the deterministic public-seam and Render-worker tests against a clean production build. The default source is copied verbatim from `examples/01-text-and-code.prose`.
