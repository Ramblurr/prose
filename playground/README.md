# Prose Playground

The Playground is an isolated static application. Its ClojureScript, npm, and vendored browser dependencies do not enter the Prose library dependency graph.

## Build the deployment artifact

Install the pinned JavaScript dependencies once, without running package scripts:

```sh
pnpm --dir playground install --frozen-lockfile --ignore-scripts
```

From the repository root, create a clean production artifact:

```sh
bb playground:build
```

The command compiles the optimized browser host and Render worker, copies every runtime asset, and verifies that the artifact contains only local, relative references. It neither installs dependencies nor fetches runtime assets.

The complete deployable site is `playground/dist/`. You can serve it from a domain root or any URL prefix.

Datastar v1.0.2 is vendored under `vendor/`; see `vendor/README.md` for its provenance, license, and machine-checked digest. The artifact includes the bundle and all third-party notices.

## Test locally

```sh
bb playground:serve
```

Open <http://localhost:8000>. To rebuild the artifact and run the deterministic public-seam and Render-worker tests, run:

```sh
bb playground:check
```

## Deploy to a static host

Copy the contents of `playground/dist/` to the directory served by your static host:

```sh
rsync -av --delete playground/dist/ user@example.org:/srv/www/playground/
```

Replace the destination with your host and document-root path. The trailing slashes copy the artifact's contents rather than the `dist` directory itself. `--delete` removes files at the destination that are absent from the new artifact, so dedicate that destination directory to the Playground.

The host needs only to serve these files over HTTP; the Playground requires no server application or runtime network fetches.
