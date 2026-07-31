import assert from "node:assert/strict";
import test from "node:test";
import seams from "../target/test/public.cjs";

const { previewDocument } = seams;

const generatedHtml = '<style>p { color: red; }</style><p style="font-weight: 900">Hello</p>';

test("keeps the exact generated fragment across independent Preview settings", () => {
  const auto = previewDocument(generatedHtml, { appearance: "auto", themeEnabled: true });
  const light = previewDocument(generatedHtml, { appearance: "light", themeEnabled: true });
  const dark = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: true });
  const unthemed = previewDocument(generatedHtml, { appearance: "dark", themeEnabled: false });

  for (const document of [auto, light, dark, unthemed]) {
    assert.ok(document.includes(generatedHtml));
  }
  assert.match(auto, /color-scheme: light dark/);
  assert.match(auto, /light-dark\(#fff, #1b1e23\)/);
  assert.notEqual(auto, light);
  assert.notEqual(light, dark);
  assert.notEqual(dark, unthemed);
});
