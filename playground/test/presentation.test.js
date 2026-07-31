import assert from "node:assert/strict";
import test from "node:test";
import seams from "../target/test/public.cjs";

const { appearancePreference, formatResult, persistAppearance } = seams;

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

test("defaults, validates, persists, and restores the appearance preference", () => {
  const storage = new MemoryStorage();

  assert.equal(appearancePreference(storage), "auto");
  for (const appearance of ["auto", "light", "dark"]) {
    persistAppearance(storage, appearance);
    assert.equal(appearancePreference(storage), appearance);
  }
  persistAppearance(storage, "invalid");
  assert.equal(appearancePreference(storage), "dark");

  const unavailable = {
    getItem() {
      throw new Error("Storage unavailable");
    },
    setItem() {
      throw new Error("Storage unavailable");
    },
  };
  assert.equal(appearancePreference(unavailable), "auto");
  assert.doesNotThrow(() => persistAppearance(unavailable, "light"));
});

test("formats intermediate results deterministically at the presentation boundary", () => {
  const compact = '[{:tag :article, :attrs {:class "example" :data-long "value"}, :content ["One" "Two" "Three"], :type :tag}]';

  assert.equal(
    formatResult(compact),
    `[{:attrs {:class "example", :data-long "value"},
  :content ["One" "Two" "Three"],
  :tag :article,
  :type :tag}]`,
  );
  assert.equal(formatResult(""), "");
});
