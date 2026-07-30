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

test("keeps the four complete Example programs canonical and ordered", async () => {
  assert.deepEqual(
    await Promise.all([
      text("../examples/01-text-and-code.prose"),
      text("../examples/02-semantic-html.prose"),
      text("../examples/03-custom-tag-function.prose"),
      text("../examples/playground/example_tags.clj"),
      text("../examples/04-html-from-a-collection.prose"),
    ]),
    [
      `◊(require '[fr.jeremyschoffen.prose.alpha.document.lib :refer [def-s]])

◊(def-s language "Prose")

Hello from ◊|language — where text and code meet.

Two plus three is ◊(+ 2 3).
`,
      `◊(require '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [article h1 h2 header p section]])

◊article{
  ◊header{
    ◊h1{Field notes}
    ◊p{A short report from the trail.}
  }
  ◊section{
    ◊h2{What we found}
    ◊p{Clear structure makes generated HTML useful.}
  }
}
`,
      `◊(require '[playground.example-tags :refer [status-label]])

Build status: ◊status-label[:ready]
`,
      `(ns playground.example-tags
  (:require
   [clojure.string :as str]
   [fr.jeremyschoffen.prose.alpha.document.lib :as lib]))

(defn status-label [status]
  (let [label (name status)]
    (lib/xml-tag :mark
                 {:class "status-label"
                  :data-length (count label)}
                 (str/upper-case label))))
`,
      `◊(require '[fr.jeremyschoffen.prose.alpha.out.html.tags :refer [h2 li ul]])

◊h2{Render stages}
◊ul{
  ◊(for [stage ["Read source"
                "Evaluate forms"
                "Compile HTML"]]
     (li stage))
}
`,
    ],
  );
});

test("provides the fixed paired-source controls without project or export actions", async () => {
  const html = await text("static/index.html");
  const options = [...html.matchAll(
    /<option value="([^"]+)">([^<]+)<\/option>/g,
  )].map(([, id, title]) => [id, title.trim()]);

  assert.deepEqual(options, [
    ["text-and-code", "Text and code"],
    ["semantic-html", "Semantic HTML"],
    ["custom-tag-function", "Custom tag function"],
    ["html-from-a-collection", "HTML from a collection"],
  ]);
  assert.match(html, /<h3 id="source-editor-label">Playground source<\/h3>/);
  assert.match(html, /<h3 id="companion-editor-label">Companion namespace<\/h3>/);
  assert.match(html, /<button id="toggle-companion"[^>]+>Show Companion namespace<\/button>/);
  assert.match(html, /<button id="reset-example" type="button" disabled>Reset<\/button>/);
  assert.doesNotMatch(
    html,
    />\s*(?:Add source|Copy|Download|Export|Package|Publish|Share)\s*</i,
  );
});
