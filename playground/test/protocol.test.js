import assert from "node:assert/strict";
import test from "node:test";
import seams from "../target/test/public.cjs";

const { protocolVersion, readinessState, renderRequest } = seams;

test("accepts the version 1 readiness handshake", () => {
  assert.equal(protocolVersion, 1);
  assert.equal(readinessState({ type: "ready", protocol: 1 }), "ready");
});

test("rejects an incompatible readiness handshake", () => {
  assert.equal(readinessState({ type: "ready", protocol: 2 }), "failed");
  assert.equal(readinessState({ type: "rendered", protocol: 1 }), null);
});

test("creates versioned complete Render requests as plain data", () => {
  assert.deepEqual(renderRequest(7, "Hello"), {
    type: "render",
    protocol: 1,
    requestId: 7,
    program: {
      source: "Hello",
      companion: null,
    },
  });
  assert.deepEqual(renderRequest(8, "◊status-label[:ready]", "(ns playground.example-tags)"), {
    type: "render",
    protocol: 1,
    requestId: 8,
    program: {
      source: "◊status-label[:ready]",
      companion: { source: "(ns playground.example-tags)" },
    },
  });
});
