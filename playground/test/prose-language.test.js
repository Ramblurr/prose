import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { ensureSyntaxTree, syntaxTree } from "@codemirror/language";
import { EditorState } from "@codemirror/state";
import { highlightTree, tagHighlighter, tags } from "@lezer/highlight";
import {
  balancedSyntax,
  clojureLanguage,
  proseLanguage,
  proseTags,
} from "../src/prose-language.js";

function tokens(source, language = proseLanguage) {
  const state = EditorState.create({ doc: source, extensions: [language] });
  const result = [];
  const tree = ensureSyntaxTree(state, state.doc.length, 10_000) ?? syntaxTree(state);
  tree.iterate({
    enter(node) {
      if (node.name !== "Document") {
        result.push([source.slice(node.from, node.to), node.name]);
      }
    },
  });
  return result;
}

const semanticHighlighter = tagHighlighter([
  { tag: proseTags.control, class: "control" },
  { tag: proseTags.command, class: "command" },
  { tag: proseTags.symbol, class: "symbol" },
  { tag: proseTags.delimiter, class: "delimiter" },
  { tag: proseTags.verbatim, class: "verbatim" },
  { tag: tags.standard(tags.variableName), class: "clojure-operator" },
  { tag: tags.keyword, class: "clojure-keyword" },
  { tag: tags.number, class: "clojure-number" },
  { tag: tags.string, class: "clojure-string" },
  { tag: tags.comment, class: "clojure-comment" },
]);

function semanticTokens(source, language = proseLanguage) {
  const state = EditorState.create({
    doc: source,
    extensions: [language, balancedSyntax],
  });
  const result = [];
  highlightTree(syntaxTree(state), semanticHighlighter, (from, to, classes) => {
    result.push([source.slice(from, to), classes]);
  });
  return result;
}

test("tokenizes all five command forms with stable Prose semantic tags", () => {
  const source = "◊tag[a][b]{outer {inner} ◊nested{x}} ◊◊raw ◊|value ◊\"verbatim\" ◊(+ 1 2)";

  assert.deepEqual(semanticTokens(source), [
    ["◊", "control"], ["tag", "command"], ["[", "delimiter"],
    ["]", "delimiter"], ["[", "delimiter"], ["]", "delimiter"],
    ["{", "delimiter"], ["{", "delimiter"], ["}", "delimiter"],
    ["◊", "control"], ["nested", "command"], ["{", "delimiter"],
    ["}", "delimiter"], ["}", "delimiter"], ["◊", "control"],
    ["◊", "control"], ["raw", "command"], ["◊", "control"],
    ["|", "control"], ["value", "symbol"], ["◊", "control"],
    ["\"", "verbatim"], ["verbatim\"", "verbatim"], ["◊", "control"],
    ["(", "delimiter"], ["+", "clojure-keyword"], ["1", "clojure-number"],
    ["2", "clojure-number"], [")", "delimiter"],
  ]);
});

test("recognizes qualified names, repeated text arguments, and adjacent commands", () => {
  const source = "◊html/div{one}{two}◊other{} ◊|scope/value";

  assert.deepEqual(tokens(source), [
    ["◊", "prose-control"], ["html/div", "prose-command"],
    ["{", "prose-delimiter"], ["}", "prose-delimiter"],
    ["{", "prose-delimiter"], ["}", "prose-delimiter"],
    ["◊", "prose-control"], ["other", "prose-command"],
    ["{", "prose-delimiter"], ["}", "prose-delimiter"],
    ["◊", "prose-control"], ["|", "prose-control"],
    ["scope/value", "prose-symbol"],
  ]);
});

test("delegates Clojure while finding nested Prose outside protected text", () => {
  const source = "◊(list :ok \"◊hidden\" ; ◊commented\n 'qualified/name ◊section{◊|shown})";
  const semantic = semanticTokens(source);

  assert.deepEqual(semantic.filter(([, style]) => style === "control"), [
    ["◊", "control"], ["◊", "control"], ["◊", "control"], ["|", "control"],
  ]);
  assert.deepEqual(semantic.filter(([, style]) => style.startsWith("clojure-")), [
    ["list", "clojure-keyword"], [":ok", "clojure-keyword"], ["\"", "clojure-string"],
    ["◊hidden\"", "clojure-string"], ["; ◊commented", "clojure-comment"],
  ]);
  assert.ok(tokens(source).some(([text]) => text === "qualified/name"));
});

test("preserves conventional call heads and protected comment forms when embedded", () => {
  assert.deepEqual(
    semanticTokens("◊(custom-op 1)").filter(([, style]) => style === "clojure-operator"),
    [["custom-op", "clojure-operator"]],
  );
  assert.deepEqual(semanticTokens("◊(comment ◊hidden)"), [
    ["◊", "control"],
    ["(", "delimiter"],
    ["comment ", "clojure-comment"],
    ["◊hidden", "clojure-comment"],
    [")", "delimiter"],
  ]);
});

test("keeps useful optimistic tokens for incomplete input", () => {
  assert.deepEqual(tokens("◊article{unfinished ◊|subject"), [
    ["◊", "prose-control"], ["article", "prose-command"],
    ["{", "prose-delimiter"], ["◊", "prose-control"],
    ["|", "prose-control"], ["subject", "prose-symbol"],
  ]);
  assert.deepEqual(tokens("◊"), [["◊", "prose-control"]]);
  assert.deepEqual(tokens("◊["), [["◊", "prose-control"], ["[", "invalid"]]);
});

test("updates tokenization after an incremental replacement", () => {
  const initial = EditorState.create({ doc: "Hello ◊|name", extensions: [proseLanguage] });
  const from = initial.doc.toString().indexOf("|name");
  const updated = initial.update({ changes: { from, to: initial.doc.length, insert: "strong{now}" } }).state;
  const result = [];
  syntaxTree(updated).iterate({
    enter(node) {
      if (node.name !== "Document") result.push([updated.sliceDoc(node.from, node.to), node.name]);
    },
  });

  assert.deepEqual(result, [
    ["◊", "prose-control"], ["strong", "prose-command"],
    ["{", "prose-delimiter"], ["}", "prose-delimiter"],
  ]);
});

test("tokenizes all canonical Examples and conventional Companion Clojure", async () => {
  const paths = [
    "examples/01-text-and-code.prose",
    "examples/02-semantic-html.prose",
    "examples/03-custom-tag-function.prose",
    "examples/04-html-from-a-collection.prose",
  ];
  for (const path of paths) {
    const source = await readFile(new URL(`../../${path}`, import.meta.url), "utf8");
    const result = tokens(source);
    assert.ok(result.some(([, name]) => name === "prose-control"), path);
    assert.equal(result.some(([, name]) => name === "invalid"), false, path);
  }

  const companion = await readFile(
    new URL("../../examples/playground/example_tags.clj", import.meta.url),
    "utf8",
  );
  const companionTokens = tokens(companion, clojureLanguage);
  assert.ok(companionTokens.some(([text]) => text === "playground.example-tags"));
  assert.equal(companionTokens.some(([, name]) => name.startsWith("prose-")), false);
});

test("tokenizes a large mixed document without losing its final command", () => {
  const unit = "Paragraph ◊section[:ok]{text ◊em{nested}} ◊(map inc [1 2 3])\n";
  const source = unit.repeat(1200) + "◊footer{done}";
  const result = tokens(source);

  assert.deepEqual(result.slice(-3), [
    ["footer", "prose-command"], ["{", "prose-delimiter"], ["}", "prose-delimiter"],
  ]);
});
