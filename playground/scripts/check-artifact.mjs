import assert from "node:assert/strict";
import { access, readFile, stat } from "node:fs/promises";
import { join } from "node:path";

const dist = new URL("../dist/", import.meta.url);
const assets = new URL("assets/", dist);
const examplePaths = [
  "examples/01-text-and-code.prose",
  "examples/02-semantic-html.prose",
  "examples/04-html-from-a-collection.prose",
];
const required = [
  "index.html",
  "assets/styles.css",
  "assets/host.js",
  "assets/worker.js",
  ...examplePaths,
];

function localAsset(reference, base) {
  assert.doesNotMatch(
    reference,
    /^(?:\/|[a-z][a-z\d+.-]*:)/i,
    `${reference} must be local and relative`,
  );
  const resolved = new URL(reference, base);
  assert.ok(resolved.href.startsWith(dist.href), `${reference} must stay inside dist`);
  return resolved;
}

async function checkCss(css) {
  const references = css.matchAll(
    /(?:url\(\s*|@import\s+(?:url\(\s*)?)["']?([^"')\s;]+)["']?\s*\)?/gi,
  );
  for (const [, reference] of references) {
    if (!reference.startsWith("data:")) await access(localAsset(reference, assets));
  }
}

async function checkJavaScript(javascript) {
  const calls = [
    ...javascript.matchAll(
      /(?:fetch|import|importScripts|Worker|EventSource)\s*\(\s*["'`]([^"'`]+)["'`]/g,
    ),
    ...javascript.matchAll(/new URL\(\s*["'`]([^"'`]+)["'`]/g),
  ];
  for (const [, reference] of calls) await access(localAsset(reference, assets));
}

await assert.rejects(access(new URL("stale-output", dist)));
for (const path of required) {
  const file = new URL(path, dist);
  await access(file);
  assert.ok((await stat(file)).size > 0, `${path} must not be empty`);
}

for (const path of examplePaths) {
  assert.equal(
    await readFile(new URL(path, dist), "utf8"),
    await readFile(new URL(`../../${path}`, import.meta.url), "utf8"),
  );
}

const html = await readFile(new URL("index.html", dist), "utf8");
for (const match of html.matchAll(/(?:src|href)=["']([^"']+)/g)) {
  await access(localAsset(match[1], dist));
}

await checkCss(await readFile(new URL("styles.css", assets), "utf8"));
await assert.rejects(checkCss('@import "https://cdn.example/styles.css";'));
await assert.rejects(checkCss('@import "/styles.css";'));

const host = await readFile(new URL("host.js", assets), "utf8");
await checkJavaScript(host);
await assert.rejects(checkJavaScript('fetch("/runtime.json")'));
await assert.rejects(checkJavaScript('import("https://cdn.example/runtime.js")'));
assert.match(host, /new Worker\(new URL\(["']\.\/worker\.js["'],import\.meta\.url\)\)/);

const worker = await readFile(new URL("worker.js", assets), "utf8");
await checkJavaScript(worker);
assert.doesNotMatch(worker, /goog\.require|COMPILED\s*=\s*false/);
assert.match(worker, /protocol/);

console.log(`verified ${required.length} local assets in ${join("playground", "dist")}`);
