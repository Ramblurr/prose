import assert from "node:assert/strict";
import test from "node:test";
import { protocolVersion, readinessState, renderRequest } from "../src/protocol.js";

test("accepts the version 1 readiness handshake", () => {
  assert.equal(protocolVersion, 1);
  assert.equal(readinessState({ type: "ready", protocol: 1 }), "ready");
});

test("rejects an incompatible readiness handshake", () => {
  assert.equal(readinessState({ type: "ready", protocol: 2 }), "failed");
  assert.equal(readinessState({ type: "rendered", protocol: 1 }), null);
});

test("creates a versioned complete single-source Render request", () => {
  assert.deepEqual(renderRequest(7, "Hello"), {
    type: "render",
    protocol: 1,
    requestId: 7,
    program: {
      source: "Hello",
      companion: null,
    },
  });
});
