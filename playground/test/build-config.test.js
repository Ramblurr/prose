import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

const projectFile = (path) => new URL(`../${path}`, import.meta.url);

async function text(path) {
  return readFile(projectFile(path), "utf8");
}

test("keeps the Playground dependency graph isolated and pinned", async () => {
  const packageJson = JSON.parse(await text("package.json"));
  const deps = await text("deps.edn");
  const lock = await text("pnpm-lock.yaml");

  assert.deepEqual(
    {
      dependencies: packageJson.dependencies,
      devDependencies: packageJson.devDependencies,
      private: packageJson.private,
    },
    {
      dependencies: {
        "@codemirror/commands": "6.10.4",
        "@codemirror/view": "6.43.6",
        "@starfederation/datastar": "1.0.0-beta.11",
      },
      devDependencies: { esbuild: "0.28.1" },
      private: true,
    },
  );
  assert.match(deps, /io\.github\.jerems\/prose \{:local\/root "\.\."\}/);
  const lockPins = [
    "'@codemirror/commands@6.10.4'",
    "'@codemirror/state@6.7.1'",
    "'@codemirror/view@6.43.6'",
    "'@starfederation/datastar@1.0.0-beta.11'",
    "esbuild@0.28.1",
  ];
  assert.deepEqual(lockPins.filter((pin) => !lock.includes(pin)), []);
});

test("documents a script-disabled frozen install", async () => {
  assert.match(
    await text("README.md"),
    /pnpm --dir playground install --frozen-lockfile --ignore-scripts/,
  );
  assert.equal(await text(".npmrc"), "ignore-scripts=true\n");
});

test("builds without installing and disables worker shims", async () => {
  const build = await text("scripts/build.sh");

  assert.doesNotMatch(build, /pnpm (?:add|install)/);
  assert.match(build, /pnpm exec esbuild/);
  assert.match(build, /-O advanced/);
  assert.match(build, /-t webworker/);
  assert.match(build, /:browser-repl false/);
  assert.match(build, /:process-shim false/);
});
