import assert from "node:assert/strict";
import test from "node:test";
import seams from "../target/test/public.cjs";

const { createRenderController } = seams;

class ControlledWorker {
  constructor() {
    this.listeners = new Map();
    this.messages = [];
    this.terminated = false;
  }

  addEventListener(type, listener) {
    const listeners = this.listeners.get(type) ?? [];
    listeners.push(listener);
    this.listeners.set(type, listeners);
  }

  emit(type, data) {
    for (const listener of this.listeners.get(type) ?? []) listener({ data });
  }

  postMessage(message) {
    this.messages.push(message);
  }

  terminate() {
    this.terminated = true;
  }
}

function controlledRuntime() {
  let nextTimerId = 1;
  const timers = new Map();
  const workers = [];
  return {
    clearTimer(id) {
      timers.delete(id);
    },
    controller(options = {}) {
      return createRenderController({
        clearTimer: this.clearTimer,
        createWorker() {
          const worker = new ControlledWorker();
          workers.push(worker);
          return worker;
        },
        setTimer: this.setTimer,
        ...options,
      });
    },
    runTimer(delay) {
      const entry = [...timers.entries()].find(([, timer]) => timer.delay === delay);
      assert.ok(entry, `Expected an active ${delay} ms timer.`);
      const [id, timer] = entry;
      timers.delete(id);
      timer.callback();
    },
    setTimer(callback, delay) {
      const id = nextTimerId;
      nextTimerId += 1;
      timers.set(id, { callback, delay });
      return id;
    },
    timerDelays() {
      return [...timers.values()].map(({ delay }) => delay);
    },
    workers,
  };
}

const ready = { type: "ready", protocol: 1 };

function rendered(requestId, html = `<p>${requestId}</p>`) {
  return {
    type: "rendered",
    protocol: 1,
    requestId,
    reader: `reader-${requestId}`,
    evaluated: `evaluated-${requestId}`,
    html,
  };
}

function failed(requestId, diagnostic) {
  return { type: "failed", protocol: 1, requestId, diagnostic };
}

test("queues work until readiness and starts its deadline only after posting", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller();

  controller.start();
  controller.render("initial source", "companion source");
  assert.deepEqual(runtime.workers[0].messages, []);
  assert.deepEqual(runtime.timerDelays(), []);

  runtime.workers[0].emit("message", ready);
  assert.deepEqual(runtime.workers[0].messages, [
    {
      type: "render",
      protocol: 1,
      requestId: 1,
      program: {
        source: "initial source",
        companion: { source: "companion source" },
      },
    },
  ]);
  assert.deepEqual(runtime.timerDelays(), [2000]);

  runtime.workers[0].emit("message", rendered(1));
  assert.deepEqual(controller.getState(), {
    diagnostic: null,
    output: {
      evaluated: "evaluated-1",
      html: "<p>1</p>",
      reader: "reader-1",
    },
    renderState: "rendered",
    requestId: 1,
    stale: false,
    workerState: "ready",
  });
  assert.deepEqual(runtime.timerDelays(), []);
});

test("debounces Auto requests and renders explicit requests immediately", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller();
  controller.start();
  runtime.workers[0].emit("message", ready);

  controller.schedule("first edit", "first companion");
  controller.schedule("second edit", "second companion");
  assert.deepEqual(runtime.timerDelays(), [350]);
  assert.deepEqual(runtime.workers[0].messages, []);

  runtime.runTimer(350);
  assert.deepEqual(runtime.workers[0].messages[0].program, {
    companion: { source: "second companion" },
    source: "second edit",
  });

  runtime.workers[0].emit("message", rendered(1));
  controller.schedule("obsolete edit", "obsolete companion");
  controller.render("explicit edit", "explicit companion");
  assert.deepEqual(runtime.timerDelays(), [2000]);
  assert.deepEqual(runtime.workers[0].messages.at(-1).program, {
    companion: { source: "explicit companion" },
    source: "explicit edit",
  });
});

test("terminates timed-out work, preserves stale output, and recovers with a new worker", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller();
  controller.start();
  runtime.workers[0].emit("message", ready);
  controller.render("successful");
  runtime.workers[0].emit("message", rendered(1, "<p>last good</p>"));

  controller.render("◊(loop [] (recur))");
  runtime.runTimer(2000);

  assert.equal(runtime.workers[0].terminated, true);
  assert.equal(runtime.workers.length, 2);
  assert.deepEqual(controller.getState(), {
    diagnostic: {
      message: "The Playground stopped execution after two seconds.",
      phase: "timeout",
      source: null,
    },
    output: {
      evaluated: "evaluated-1",
      html: "<p>last good</p>",
      reader: "reader-1",
    },
    renderState: "failed",
    requestId: 2,
    stale: true,
    workerState: "initializing",
  });

  runtime.workers[1].emit("message", ready);
  controller.render("valid after timeout");
  runtime.workers[1].emit("message", rendered(3));
  assert.equal(controller.getState().output.html, "<p>3</p>");
  assert.equal(controller.getState().stale, false);
});

test("supersedes active work and ignores every obsolete reply", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller();
  controller.start();
  runtime.workers[0].emit("message", ready);
  controller.render("slow source");

  controller.render("current source");
  assert.equal(runtime.workers[0].terminated, true);
  assert.equal(runtime.workers.length, 2);
  runtime.workers[0].emit("message", rendered(1, "<p>obsolete</p>"));
  runtime.workers[0].emit(
    "message",
    failed(1, { phase: "compile", source: "Playground", message: "obsolete" }),
  );
  assert.equal(controller.getState().output, null);
  assert.equal(controller.getState().diagnostic, null);

  runtime.workers[1].emit("message", ready);
  assert.equal(runtime.workers[1].messages[0].requestId, 2);
  runtime.workers[1].emit("message", rendered(2, "<p>current</p>"));
  assert.equal(controller.getState().output.html, "<p>current</p>");

  runtime.workers[1].emit("message", rendered(1, "<p>wrong identity</p>"));
  assert.equal(controller.getState().output.html, "<p>current</p>");
});

test("distinguishes first failure from stale output without changing diagnostic detail", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller();
  const diagnostic = {
    expected: "a command",
    failedText: "◊",
    message: "Prose reader error at line 2, column 9: expected a command.",
    phase: "read",
    range: {
      endColumn: 9,
      endIndex: 17,
      endLine: 2,
      startColumn: 8,
      startIndex: 16,
      startLine: 2,
    },
    source: "Playground",
    position: { column: 9, index: 17, line: 2 },
  };
  controller.start();
  runtime.workers[0].emit("message", ready);

  controller.render("bad first source");
  runtime.workers[0].emit("message", failed(1, diagnostic));
  assert.deepEqual(
    {
      diagnostic: controller.getState().diagnostic,
      output: controller.getState().output,
      stale: controller.getState().stale,
    },
    { diagnostic, output: null, stale: false },
  );

  controller.render("good source");
  runtime.workers[0].emit("message", rendered(2, "<p>good</p>"));
  controller.render("bad later source");
  runtime.workers[0].emit("message", failed(3, diagnostic));
  assert.deepEqual(
    {
      output: controller.getState().output,
      stale: controller.getState().stale,
    },
    {
      output: {
        evaluated: "evaluated-2",
        html: "<p>good</p>",
        reader: "reader-2",
      },
      stale: true,
    },
  );
});

test("reports startup and protocol failures as visible first-error states", () => {
  for (const fail of [
    (worker) => worker.emit("error", new Error("load failed")),
    (worker) => worker.emit("message", { type: "ready", protocol: 2 }),
  ]) {
    const runtime = controlledRuntime();
    const controller = runtime.controller();
    controller.start();
    fail(runtime.workers[0]);

    assert.equal(runtime.workers[0].terminated, true);
    assert.deepEqual(controller.getState(), {
      diagnostic: {
        message: "The render worker could not initialize.",
        phase: "initialization",
        source: null,
      },
      output: null,
      renderState: "failed",
      requestId: 0,
      stale: false,
      workerState: "failed",
    });
  }
});

test("reports synchronous worker-construction failure", () => {
  const runtime = controlledRuntime();
  const controller = runtime.controller({
    createWorker() {
      throw new Error("Worker construction failed.");
    },
  });

  assert.doesNotThrow(() => controller.start());
  assert.deepEqual(controller.getState(), {
    diagnostic: {
      message: "The render worker could not initialize.",
      phase: "initialization",
      source: null,
    },
    output: null,
    renderState: "failed",
    requestId: 0,
    stale: false,
    workerState: "failed",
  });
});

test("reports a synchronous failure while constructing a timeout replacement", () => {
  const runtime = controlledRuntime();
  const states = [];
  let constructions = 0;
  const controller = runtime.controller({
    createWorker() {
      constructions += 1;
      if (constructions > 1) throw new Error("Replacement construction failed.");
      const worker = new ControlledWorker();
      runtime.workers.push(worker);
      return worker;
    },
    onChange(state) {
      states.push(structuredClone(state));
    },
  });
  controller.start();
  runtime.workers[0].emit("message", ready);
  controller.render("successful");
  runtime.workers[0].emit("message", rendered(1, "<p>last good</p>"));
  controller.render("◊(loop [] (recur))");

  assert.doesNotThrow(() => runtime.runTimer(2000));
  const diagnosticPhases = states
    .filter(({ diagnostic }) => diagnostic)
    .map(({ diagnostic }) => diagnostic.phase);
  assert.equal(diagnosticPhases[0], "timeout");
  assert.equal(diagnosticPhases.at(-1), "initialization");
  assert.deepEqual(controller.getState(), {
    diagnostic: {
      message: "The render worker could not initialize.",
      phase: "initialization",
      source: null,
    },
    output: {
      evaluated: "evaluated-1",
      html: "<p>last good</p>",
      reader: "reader-1",
    },
    renderState: "failed",
    requestId: 2,
    stale: true,
    workerState: "failed",
  });
});
