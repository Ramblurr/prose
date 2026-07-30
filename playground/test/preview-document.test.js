import assert from "node:assert/strict";
import test from "node:test";
import { previewDocument } from "../src/preview-document.js";

const generatedHtml = '<style>p { color: red; }</style><p style="font-weight: 900">Hello</p>';

test("keeps the exact generated fragment across independent Preview settings", () => {
  const light = previewDocument(generatedHtml, { appearance: "light", themeEnabled: true });
  const dark = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: true });
  const unthemed = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: false });

  assert.ok(light.includes(generatedHtml));
  assert.ok(dark.includes(generatedHtml));
  assert.ok(unthemed.includes(generatedHtml));
  assert.notEqual(light, dark);
  assert.notEqual(dark, unthemed);
});
