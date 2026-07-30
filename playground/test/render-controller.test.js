import assert from "node:assert/strict";
import test from "node:test";
import { renderOutcome } from "../src/render-controller.js";

test("publishes only the current versioned Render outcome", () => {
  const rendered = {
    type: "rendered",
    protocol: 1,
    requestId: 3,
    reader: "reader",
    evaluated: "evaluated",
    html: "<p>current</p>",
  };
  const failed = {
    type: "failed",
    protocol: 1,
    requestId: 3,
    diagnostic: { phase: "read", message: "Unclosed command." },
  };

  assert.deepEqual(
    {
      failed: renderOutcome(failed, 3),
      incompatible: renderOutcome({ ...rendered, protocol: 2 }, 3),
      rendered: renderOutcome(rendered, 3),
      stale: renderOutcome({ ...rendered, requestId: 2 }, 3),
    },
    {
      failed: {
        previewHtml: "",
        signals: {
          diagnosticMessage: "Unclosed command.",
          diagnosticPhase: "read",
          evaluatedResult: "",
          htmlResult: "",
          readerResult: "",
          renderStatus: "Render failed",
          workerStatusDetail: "read: Unclosed command.",
        },
      },
      incompatible: null,
      rendered: {
        previewHtml: "<p>current</p>",
        signals: {
          diagnosticMessage: "",
          evaluatedResult: "evaluated",
          htmlResult: "<p>current</p>",
          readerResult: "reader",
          renderStatus: "Rendered",
          workerStatusDetail: "Rendered request 3.",
        },
      },
      stale: null,
    },
  );
});
