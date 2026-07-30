import assert from "node:assert/strict";
import test from "node:test";
import { protocolVersion, readinessState } from "../src/protocol.js";

test("accepts the version 1 readiness handshake", () => {
  assert.equal(protocolVersion, 1);
  assert.equal(readinessState({ type: "ready", protocol: 1 }), "ready");
});

test("rejects an incompatible readiness handshake", () => {
  assert.equal(readinessState({ type: "ready", protocol: 2 }), "failed");
  assert.equal(readinessState({ type: "rendered", protocol: 1 }), null);
});
