import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";
import { Worker } from "node:worker_threads";
import { renderRequest } from "../src/protocol.js";

const defaultExample = await readFile(
  new URL("../../examples/01-text-and-code.prose", import.meta.url),
  "utf8",
);

function nextMessage(worker) {
  return new Promise((resolve, reject) => {
    let timer;
    const cleanup = () => {
      clearTimeout(timer);
      worker.off("error", rejectMessage);
      worker.off("message", resolveMessage);
    };
    const rejectMessage = (error) => {
      cleanup();
      reject(error);
    };
    const resolveMessage = (message) => {
      cleanup();
      resolve(message);
    };
    timer = setTimeout(() => rejectMessage(new Error("Timed out waiting for worker message.")), 5000);
    worker.once("error", rejectMessage);
    worker.once("message", resolveMessage);
  });
}

async function readyWorker(t) {
  const worker = new Worker(new URL("./fixtures/worker-runner.mjs", import.meta.url));
  t.after(() => worker.terminate());
  assert.deepEqual(await nextMessage(worker), { type: "ready", protocol: 1 });
  return worker;
}

async function render(worker, requestId, source) {
  worker.postMessage(renderRequest(requestId, source));
  return nextMessage(worker);
}

test("renders the canonical Text and code Example through the production worker", async (t) => {
  const worker = await readyWorker(t);

  assert.deepEqual(await render(worker, 1, defaultExample), {
    type: "rendered",
    protocol: 1,
    requestId: 1,
    reader: '[(require (quote [fr.jeremyschoffen.prose.alpha.document.lib :refer [def-s]])) "\\n\\n" (def-s language "Prose") "\\n\\nHello from " language " — where text and code meet.\\n\\nTwo plus three is " (+ 2 3) ".\\n"]',
    evaluated: '[nil "\\n\\n" "" "\\n\\nHello from " "Prose" " — where text and code meet.\\n\\nTwo plus three is " 5 ".\\n"]',
    html: "\n\n\n\nHello from Prose — where text and code meet.\n\nTwo plus three is 5.\n",
  });
});

test("forks fresh restricted evaluation state for every Render", async (t) => {
  const worker = await readyWorker(t);
  const defined = await render(
    worker,
    2,
    `◊(require '[fr.jeremyschoffen.prose.alpha.document.lib :refer [def-s]])

◊(def-s render-secret "private")

◊|render-secret`,
  );
  const nextRender = await render(worker, 3, "◊|render-secret");
  const browserAccess = await render(worker, 4, "◊js/document");
  const networkAccess = await render(worker, 5, "◊js/fetch");
  const dependencyAccess = await render(
    worker,
    6,
    "◊(require '[cljs.core.async :as async])",
  );

  assert.deepEqual(
    {
      browserAccess: {
        phase: browserAccess.diagnostic?.phase,
        type: browserAccess.type,
      },
      defined: defined.type,
      dependencyAccess: {
        phase: dependencyAccess.diagnostic?.phase,
        type: dependencyAccess.type,
      },
      networkAccess: {
        phase: networkAccess.diagnostic?.phase,
        type: networkAccess.type,
      },
      nextRender: {
        phase: nextRender.diagnostic?.phase,
        type: nextRender.type,
      },
    },
    {
      browserAccess: { phase: "playground-evaluate", type: "failed" },
      defined: "rendered",
      dependencyAccess: { phase: "playground-evaluate", type: "failed" },
      networkAccess: { phase: "playground-evaluate", type: "failed" },
      nextRender: { phase: "playground-evaluate", type: "failed" },
    },
  );
});

test("blocks indirect worker-global and network recovery", async (t) => {
  const worker = await readyWorker(t);
  const globalAccess = await render(
    worker,
    7,
    `◊(let [object (js-obj)
        constructor (aget object "constructor")
        Function (aget constructor "constructor")
        global ((Function "return globalThis"))]
    (aget (aget global "process") "version"))`,
  );
  const indirectNetworkAccess = await render(
    worker,
    8,
    `◊(let [object (js-obj)
        constructor (aget object "constructor")
        Function (aget constructor "constructor")
        global ((Function "return globalThis"))]
    (boolean (aget global "fetch")))`,
  );

  assert.deepEqual(
    [globalAccess, indirectNetworkAccess].map(({ diagnostic, type }) => ({
      phase: diagnostic?.phase,
      type,
    })),
    [
      { phase: "playground-evaluate", type: "failed" },
      { phase: "playground-evaluate", type: "failed" },
    ],
  );
});

test("returns honest Reader, evaluation, and compilation Diagnostics", async (t) => {
  const worker = await readyWorker(t);
  const readerSource = "line one\nbefore ◊";
  const evaluationSource = "before ◊|missing-symbol";
  const readerFailure = await render(worker, 9, readerSource);
  const evaluationFailure = await render(worker, 10, evaluationSource);
  const compilationFailure = await render(
    worker,
    11,
    "◊(identity {:type :tag :tag 1 :attrs {} :content []})",
  );

  assert.deepEqual(readerFailure.diagnostic, {
    expected: "a command",
    failedText: "◊",
    message: "Prose reader error at line 2, column 9: expected a command.",
    phase: "read",
    position: { column: 9, index: 17, line: 2 },
    range: {
      endColumn: 9,
      endIndex: 17,
      endLine: 2,
      startColumn: 8,
      startIndex: 16,
      startLine: 2,
    },
    source: "Playground",
  });
  assert.deepEqual(evaluationFailure.diagnostic, {
    message: "Could not resolve symbol: missing-symbol",
    phase: "playground-evaluate",
    range: {
      endColumn: evaluationSource.length + 1,
      endIndex: evaluationSource.length,
      endLine: 1,
      startColumn: 8,
      startIndex: 7,
      startLine: 1,
    },
    source: "Playground",
  });
  assert.deepEqual(compilationFailure.diagnostic, {
    message: "no conversion to symbol",
    phase: "compile",
    source: "Playground",
  });
});
