import assert from "node:assert/strict";
import test from "node:test";
import { previewDocument } from "../src/preview-document.js";

const generatedHtml = '<style>p { color: red; }</style><p style="font-weight: 900">Hello</p>';

test("keeps generated HTML exact while changing only Preview presentation", () => {
  const themed = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: true });
  const unthemed = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: false });

  assert.equal(generatedHtml, '<style>p { color: red; }</style><p style="font-weight: 900">Hello</p>');
  assert.ok(themed.includes(generatedHtml));
  assert.ok(unthemed.includes(generatedHtml));
  assert.match(themed, /id="playground-preview-theme"/);
  assert.doesNotMatch(unthemed, /id="playground-preview-theme"/);
  assert.ok(themed.indexOf("playground-preview-theme") < themed.indexOf(generatedHtml));
});

test("uses only zero-specificity Preview-theme selectors and separate appearance palettes", () => {
  const light = previewDocument("<h1>Title</h1>", { appearance: "light", themeEnabled: true });
  const dark = previewDocument("<h1>Title</h1>", { appearance: "dark", themeEnabled: true });
  const selectors = [...light.matchAll(/^  (\S[^\n{]+) \{/gm)].map(([, selector]) => selector);

  assert.notEqual(light, dark);
  assert.match(light, /--preview-background: #fff/);
  assert.match(dark, /--preview-background: #1b1e23/);
  assert.ok(selectors.length > 0);
  assert.ok(selectors.every((selector) => selector.startsWith(":where(")));
});
