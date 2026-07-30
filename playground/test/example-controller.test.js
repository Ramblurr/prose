import assert from "node:assert/strict";
import test from "node:test";
import { createExampleController } from "../src/example-controller.js";

const canonicalExamples = [
  { id: "text-and-code", source: "Text canonical\n", title: "Text and code" },
  { id: "semantic-html", source: "Semantic canonical\n", title: "Semantic HTML" },
  { id: "html-from-a-collection", source: "Collection canonical\n", title: "HTML from a collection" },
];

class MemoryStorage {
  constructor(value = null) {
    this.value = value;
  }

  getItem() {
    return this.value;
  }

  setItem(_key, value) {
    this.value = value;
  }
}

function playground(storage = new MemoryStorage()) {
  const visible = { renders: [], selectedExample: null, source: null };
  const controller = createExampleController({
    examples: canonicalExamples,
    onActivate({ selectedExample, source }) {
      visible.selectedExample = selectedExample;
      visible.source = source;
      visible.renders.push(source);
    },
    storage,
  });
  return { controller, storage, visible };
}

test("selects and exactly resets a canonical Example", () => {
  const { controller, visible } = playground();
  controller.start();
  controller.edit("Edited text\n");
  controller.select("semantic-html");

  assert.deepEqual(visible, {
    renders: ["Text canonical\n", "Semantic canonical\n"],
    selectedExample: "semantic-html",
    source: "Semantic canonical\n",
  });

  controller.edit("Edited semantic source\n");
  controller.reset();

  assert.deepEqual(visible, {
    renders: ["Text canonical\n", "Semantic canonical\n", "Semantic canonical\n"],
    selectedExample: "semantic-html",
    source: "Semantic canonical\n",
  });
});

test("reload restores authored source and Reset target but activates a fresh Render", () => {
  const first = playground();
  first.controller.start();
  first.controller.select("html-from-a-collection");
  first.controller.edit("Authored collection source\n");

  const reloaded = playground(first.storage);
  assert.deepEqual(reloaded.visible, {
    renders: [],
    selectedExample: null,
    source: null,
  });

  reloaded.controller.start();
  assert.deepEqual(reloaded.visible, {
    renders: ["Authored collection source\n"],
    selectedExample: "html-from-a-collection",
    source: "Authored collection source\n",
  });

  reloaded.controller.reset();
  assert.equal(reloaded.visible.source, "Collection canonical\n");
  assert.deepEqual(reloaded.visible.renders, [
    "Authored collection source\n",
    "Collection canonical\n",
  ]);
});

test("reload discards persisted transient state", () => {
  const storage = new MemoryStorage(JSON.stringify({
    diagnostic: { phase: "read" },
    output: { html: "stale" },
    popoverOpen: true,
    renderState: "failed",
    selectedExample: "semantic-html",
    shorthandPending: true,
    source: "Restored authored source\n",
    version: 1,
    workerState: "ready",
  }));
  const { controller, visible } = playground(storage);

  controller.start();

  assert.deepEqual(controller.getState(), {
    selectedExample: "semantic-html",
    source: "Restored authored source\n",
    title: "Semantic HTML",
  });
  assert.deepEqual(visible.renders, ["Restored authored source\n"]);
});

test("corrupt, unsupported, or unavailable persistence falls back safely", () => {
  const records = [
    "not json",
    JSON.stringify({ version: 2, source: "unsupported", selectedExample: "semantic-html" }),
    JSON.stringify({ version: 1, source: 42, selectedExample: "semantic-html" }),
    JSON.stringify({ version: 1, source: "unknown", selectedExample: "missing" }),
  ];

  for (const record of records) {
    const { controller, visible } = playground(new MemoryStorage(record));
    assert.doesNotThrow(() => controller.start());
    assert.equal(visible.selectedExample, "text-and-code");
    assert.equal(visible.source, "Text canonical\n");
  }

  const unavailableStorage = {
    getItem() {
      throw new Error("Storage unavailable");
    },
    setItem() {
      throw new Error("Storage unavailable");
    },
  };
  const { controller, visible } = playground(unavailableStorage);
  assert.doesNotThrow(() => controller.start());
  assert.doesNotThrow(() => controller.edit("Still editable\n"));
  assert.equal(visible.source, "Text canonical\n");
});
