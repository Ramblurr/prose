import assert from "node:assert/strict";
import test from "node:test";
import seams from "../target/test/public.cjs";

const { createExampleController } = seams;

const canonicalExamples = [
  {
    companion: null,
    id: "text-and-code",
    source: "Text canonical\n",
    title: "Text and code",
  },
  {
    companion: null,
    id: "semantic-html",
    source: "Semantic canonical\n",
    title: "Semantic HTML",
  },
  {
    companion: "(ns playground.example-tags)\n",
    id: "custom-tag-function",
    source: "Custom canonical\n",
    title: "Custom tag function",
  },
  {
    companion: null,
    id: "html-from-a-collection",
    source: "Collection canonical\n",
    title: "HTML from a collection",
  },
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
  const activations = [];
  const controller = createExampleController({
    examples: canonicalExamples,
    onActivate(program) {
      activations.push(structuredClone(program));
    },
    storage,
  });
  return { activations, controller, storage };
}

test("selects, edits, and exactly resets the paired Example", () => {
  const { activations, controller } = playground();
  controller.start();
  controller.select("custom-tag-function");
  controller.editSource("Authored Playground source\n");
  controller.editCompanion("(ns playground.example-tags)\n(def edited true)\n");

  assert.deepEqual(controller.getState(), {
    companion: "(ns playground.example-tags)\n(def edited true)\n",
    selectedExample: "custom-tag-function",
    source: "Authored Playground source\n",
    title: "Custom tag function",
  });

  controller.reset();
  assert.deepEqual(controller.getState(), {
    companion: "(ns playground.example-tags)\n",
    selectedExample: "custom-tag-function",
    source: "Custom canonical\n",
    title: "Custom tag function",
  });
  assert.deepEqual(activations.map(({ selectedExample }) => selectedExample), [
    "text-and-code",
    "custom-tag-function",
    "custom-tag-function",
  ]);
});

test("switching to a single-source Example clears the Companion", () => {
  const { controller } = playground();
  controller.start();
  controller.select("custom-tag-function");
  controller.editCompanion("(ns playground.example-tags)\n(def leaked true)\n");

  controller.select("semantic-html");

  assert.deepEqual(controller.getState(), {
    companion: null,
    selectedExample: "semantic-html",
    source: "Semantic canonical\n",
    title: "Semantic HTML",
  });
});

test("reload restores the complete authored program and activates a fresh Render", () => {
  const first = playground();
  first.controller.start();
  first.controller.select("custom-tag-function");
  first.controller.editSource("Authored custom source\n");
  first.controller.editCompanion("(ns playground.example-tags)\n(def authored true)\n");

  const reloaded = playground(first.storage);
  assert.deepEqual(reloaded.activations, []);

  reloaded.controller.start();
  assert.deepEqual(reloaded.activations, [{
    companion: "(ns playground.example-tags)\n(def authored true)\n",
    selectedExample: "custom-tag-function",
    source: "Authored custom source\n",
    title: "Custom tag function",
  }]);

  reloaded.controller.reset();
  assert.deepEqual(reloaded.controller.getState(), {
    companion: "(ns playground.example-tags)\n",
    selectedExample: "custom-tag-function",
    source: "Custom canonical\n",
    title: "Custom tag function",
  });
});

test("reload discards persisted transient state", () => {
  const storage = new MemoryStorage(JSON.stringify({
    diagnostic: { phase: "read" },
    output: { html: "stale" },
    popoverOpen: true,
    companionVisible: true,
    renderState: "failed",
    selectedExample: "semantic-html",
    shorthandPending: true,
    source: "Restored authored source\n",
    version: 1,
    workerState: "ready",
  }));
  const { activations, controller } = playground(storage);

  controller.start();

  assert.deepEqual(controller.getState(), {
    companion: null,
    selectedExample: "semantic-html",
    source: "Restored authored source\n",
    title: "Semantic HTML",
  });
  assert.deepEqual(activations, [controller.getState()]);
});

test("invalid paired persistence and unavailable storage fall back safely", () => {
  const records = [
    "not json",
    JSON.stringify({ version: 2, source: "unsupported", selectedExample: "semantic-html" }),
    JSON.stringify({ version: 1, source: 42, selectedExample: "semantic-html" }),
    JSON.stringify({ version: 1, source: "unknown", selectedExample: "missing" }),
    JSON.stringify({
      companion: null,
      companionVisible: true,
      selectedExample: "custom-tag-function",
      source: "incomplete custom program",
      version: 1,
    }),
  ];

  for (const record of records) {
    const { controller } = playground(new MemoryStorage(record));
    assert.doesNotThrow(() => controller.start());
    assert.equal(controller.getState().selectedExample, "text-and-code");
    assert.equal(controller.getState().companion, null);
  }

  const unavailableStorage = {
    getItem() {
      throw new Error("Storage unavailable");
    },
    setItem() {
      throw new Error("Storage unavailable");
    },
  };
  const { controller } = playground(unavailableStorage);
  assert.doesNotThrow(() => controller.start());
  assert.doesNotThrow(() => controller.editSource("Still editable\n"));
  assert.equal(controller.getState().source, "Still editable\n");
});
