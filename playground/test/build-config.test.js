import assert from "node:assert/strict";
import { readFile, readdir } from "node:fs/promises";
import test from "node:test";
import seams from "../target/test/public.cjs";

const projectFile = (path) => new URL(`../${path}`, import.meta.url);
const { createHostActions } = seams;

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
        "@codemirror/language": "6.12.4",
        "@codemirror/legacy-modes": "6.5.3",
        "@codemirror/state": "6.7.1",
        "@codemirror/view": "6.43.6",
        "@lezer/highlight": "1.2.3",
      },
      devDependencies: { esbuild: "0.28.1" },
      private: true,
    },
  );
  assert.match(deps, /io\.github\.jerems\/prose \{:local\/root "\.\."\}/);
  const lockPins = [
    "'@codemirror/commands@6.10.4'",
    "'@codemirror/language@6.12.4'",
    "'@codemirror/legacy-modes@6.5.3'",
    "'@codemirror/state@6.7.1'",
    "'@codemirror/view@6.43.6'",
    "'@lezer/highlight@1.2.3'",
    "esbuild@0.28.1",
  ];
  assert.deepEqual(lockPins.filter((pin) => !lock.includes(pin)), []);
  assert.doesNotMatch(lock, /starfederation\/datastar/);
  assert.match(await text("vendor/datastar.js"), /^\/\/ Datastar v1\.0\.2$/m);
  assert.match(await text("vendor/README.md"), /v1\.0\.2/);
});

test("documents a script-disabled frozen install", async () => {
  assert.match(
    await text("README.md"),
    /pnpm --dir playground install --frozen-lockfile --ignore-scripts/,
  );
  assert.equal(await text(".npmrc"), "ignore-scripts=true\n");
});

test("builds separate optimized ClojureScript host and worker targets without installing", async () => {
  const build = await text("scripts/build.sh");
  const check = await text("scripts/check.sh");
  const justfile = await text("../justfile");

  assert.doesNotMatch(build, /pnpm (?:add|install)/);
  for (const script of [build, check]) {
    assert.match(script, /exec 9>target\/\.playground\.lock[\s\S]+flock 9/);
  }
  assert.match(build, /rm -rf dist target\/host target\/worker/);
  assert.doesNotMatch(build, /rm -rf dist target(?:\s|$)/);
  assert.match(check, /rm -rf target\/test/);
  assert.equal([...build.matchAll(/-O advanced/g)].length, 2);
  assert.match(build, /-t bundle/);
  assert.match(build, /-c prose\.playground\.host/);
  assert.match(build, /pnpm exec esbuild target\/host\.js/);
  assert.match(build, /-t webworker/);
  assert.match(build, /-c prose\.playground\.worker/);
  assert.equal([...build.matchAll(/:browser-repl false/g)].length, 2);
  assert.equal([...build.matchAll(/:process-shim false/g)].length, 2);
  assert.equal([...build.matchAll(/:externs \["externs\.js"\]/g)].length, 2);
  assert.equal([...check.matchAll(/:externs \["externs\.js"\]/g)].length, 1);
  const externs = await text("externs.js");
  assert.match(externs, /EditorView\.contentAttributes/);
  assert.match(externs, /EditorView\.updateListener/);
  assert.match(externs, /HighlightTags\.prototype\.null/);
  assert.match(build, /cp vendor\/datastar\.js dist\/assets\/datastar\.js/);
  assert.match(
    build,
    /cp vendor\/DATASTAR-LICENSE\.md dist\/assets\/DATASTAR-LICENSE\.md/,
  );
  assert.ok(
    build.indexOf("-c prose.playground.worker") <
      build.indexOf("node scripts/check-artifact.mjs"),
  );
  assert.doesNotMatch(justfile, /node playground\/scripts\/check-artifact\.mjs/);
});

test("keeps every hand-authored production module in ClojureScript", async () => {
  const sourceFiles = await readdir(projectFile("src"), { recursive: true });
  const productionJavaScript = sourceFiles.filter((path) => path.endsWith(".js"));

  assert.deepEqual(productionJavaScript, []);
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
  assert.match(html, /<button id="reset-example"[\s\S]+?>Reset<\/button>/);
  assert.doesNotMatch(
    html,
    />\s*(?:Add source|Copy|Download|Export|Package|Publish|Share)\s*</i,
  );
});

test("installs Prose-aware source editing and literal Companion editing", async () => {
  const html = await text("static/index.html");
  const styles = await text("static/styles.css");

  assert.match(html, /Type <kbd>@<\/kbd> directly for <code>◊<\/code>/);
  assert.match(html, /without\s+interruption for a literal <code>@<\/code>/);
  assert.match(styles, /\.tok-prose-command/);
  assert.match(styles, /body\[data-appearance="dark"\][\s\S]+--syntax-command/);
});

test("uses native responsive interface controls with their approved ownership", async () => {
  const html = await text("static/index.html");
  const header = html.match(/<header class="app-header">[\s\S]+?<\/header>/)?.[0] ?? "";
  const source = html.match(/<section class="pane source-pane"[\s\S]+?<div id="editor-stack"/)?.[0] ?? "";
  const resultRadios = [...html.matchAll(/type="radio" name="result-view"/g)];

  assert.match(header, /id="auto-render"/);
  assert.match(header, /id="render"/);
  assert.match(header, /id="settings-trigger"[^>]+popovertarget="settings-popover"/);
  assert.doesNotMatch(header, /id="example-select"|id="reset-example"/);
  assert.match(source, /id="example-select"/);
  assert.match(source, /id="reset-example"/);
  assert.match(html, /id="settings-popover" class="settings-popover" popover="auto"/);
  assert.match(html, /id="preview-theme" type="checkbox" role="switch" checked/);
  assert.equal(resultRadios.length, 4);
  assert.ok(html.indexOf("source-pane") < html.indexOf("result-pane"));
  assert.doesNotMatch(html, /role="tab(?:list)?"|tabindex=/);
});

test("gives every declared signal standard Datastar 1.0.2 ownership", async () => {
  const html = await text("static/index.html");
  const signalDeclaration = html.match(/data-signals="([^"]+)"/)?.[1] ?? "";
  const eventBinding = html.match(
    /data-on:prose-playground-state__window="([^"]+)"/s,
  )?.[1] ?? "";
  assert.doesNotMatch(eventBinding, /data-on:/);
  const declaredSignals = [
    ...signalDeclaration.matchAll(/(?:^|[,{]\s*)([A-Za-z]\w*):/g),
  ]
    .map(([, signal]) => signal)
    .sort();
  const patchedSignals = [...eventBinding.matchAll(
    /\$(\w+)\s*=\s*evt\.detail\.\1\s*\?\?\s*\$\1/g,
  )].map(([, signal]) => signal);
  const boundSignals = [...html.matchAll(/data-bind="(\w+)"/g)]
    .map(([, signal]) => signal);
  const expressionSignals = [...html.matchAll(/\$(\w+)\s*=/g)]
    .map(([, signal]) => signal);
  const ownedSignals = [
    ...new Set([...patchedSignals, ...boundSignals, ...expressionSignals]),
  ].sort();

  assert.ok(
    html.indexOf("./assets/host.js") < html.indexOf("./assets/datastar.js"),
  );
  assert.doesNotMatch(html, /data-(?:attr|class|on)-[a-z]/);
  assert.deepEqual(ownedSignals, declaredSignals);
  assert.match(html, /data-bind="appearance"/);
  assert.match(html, /data-bind="previewThemeEnabled"/);
  assert.match(html, /data-bind="resultView"/);
  assert.match(
    html,
    /data-on:click="\$companionVisible = !\$companionVisible"/,
  );
  assert.match(
    html,
    /data-on:prose-playground-edit__window="\$autoRender && globalThis\.prosePlayground\.schedule\(\)"/,
  );
  assert.match(
    html,
    /data-on:prose-playground-program__window="[\s\S]+\$companionVisible = evt\.detail\.companion !== null/,
  );
  assert.match(
    html,
    /data-on:prose-playground-render-state__window="[\s\S]+\$workerReady = evt\.detail\.workerState === 'ready'/,
  );
  assert.match(html, /data-attr:data-appearance="\$appearance"/);
  assert.match(
    html,
    /data-attr:srcdoc="\$htmlResult && globalThis\.prosePlayground\.previewDocument\(\$htmlResult, \$appearance, \$previewThemeEnabled\)"/,
  );
});

test("executes Auto transitions through the current-program-only host action", async () => {
  const html = await text("static/index.html");
  const changeExpression = html.match(
    /id="auto-render"[\s\S]+?data-on:change="([^"]+)"/,
  )?.[1];
  const editExpression = html.match(
    /data-on:prose-playground-edit__window="([^"]+)"/,
  )?.[1];
  const calls = [];
  const actions = createHostActions({
    cancelScheduled: (...args) => calls.push(["cancelScheduled", args]),
    previewDocument: () => {},
    render: () => {},
    resetExample: () => {},
    scheduleCurrent: (...args) => calls.push(["scheduleCurrent", args]),
    selectExample: () => {},
  });
  const context = { prosePlayground: actions };
  const changeAuto = new Function("globalThis", "$autoRender", changeExpression);
  const editWithAuto = new Function("globalThis", "$autoRender", editExpression);

  changeAuto(context, false);
  changeAuto(context, true);
  editWithAuto(context, true);
  editWithAuto(context, false);
  context.prosePlayground.schedule("foreign", "payload");

  assert.deepEqual(calls, [
    ["cancelScheduled", []],
    ["scheduleCurrent", []],
    ["scheduleCurrent", []],
    ["scheduleCurrent", []],
  ]);
});
