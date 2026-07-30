import { currentRenderResponse, readinessState, renderRequest } from "./protocol.js";

const autoDelay = 350;
const executionDeadline = 2000;
const initializationDiagnostic = {
  message: "The render worker could not initialize.",
  phase: "initialization",
  source: null,
};
const timeoutDiagnostic = {
  message: "The Playground stopped execution after two seconds.",
  phase: "timeout",
  source: null,
};

function initialState() {
  return {
    diagnostic: null,
    output: null,
    renderState: "waiting",
    requestId: 0,
    stale: false,
    workerState: "initializing",
  };
}

export function createRenderController({
  clearTimer = clearTimeout,
  createWorker,
  onChange = () => {},
  setTimer = setTimeout,
} = {}) {
  let activeRequest = null;
  let autoTimer = null;
  let deadlineTimer = null;
  let lastSuccessfulOutput = null;
  let pendingRequest = null;
  let started = false;
  let state = initialState();
  let worker = null;
  let workerReady = false;

  function publish(patch) {
    state = { ...state, ...patch };
    onChange(state);
  }

  function clearAutoTimer() {
    if (autoTimer !== null) clearTimer(autoTimer);
    autoTimer = null;
  }

  function clearDeadline() {
    if (deadlineTimer !== null) clearTimer(deadlineTimer);
    deadlineTimer = null;
  }

  function terminateWorker() {
    if (worker) worker.terminate();
    worker = null;
    workerReady = false;
  }

  function fail(diagnostic) {
    clearDeadline();
    activeRequest = null;
    publish({
      diagnostic,
      output: lastSuccessfulOutput,
      renderState: "failed",
      stale: lastSuccessfulOutput !== null,
    });
  }

  function startDeadline(requestId) {
    deadlineTimer = setTimer(() => {
      if (activeRequest?.id !== requestId) return;
      terminateWorker();
      fail(timeoutDiagnostic);
      spawnWorker();
    }, executionDeadline);
  }

  function sendPendingRequest() {
    if (!workerReady || !pendingRequest) return;
    const request = pendingRequest;
    pendingRequest = null;
    activeRequest = request;
    publish({
      diagnostic: null,
      renderState: "rendering",
      requestId: request.id,
      stale: false,
    });
    worker.postMessage(renderRequest(request.id, request.source));
    startDeadline(request.id);
  }

  function handleMessage(candidate, message) {
    if (candidate !== worker) return;

    const readiness = readinessState(message);
    if (readiness === "ready") {
      workerReady = true;
      publish({ workerState: "ready" });
      sendPendingRequest();
      return;
    }
    if (readiness === "failed") {
      terminateWorker();
      publish({ workerState: "failed" });
      fail(initializationDiagnostic);
      return;
    }

    const response = currentRenderResponse(message, state.requestId);
    if (!response || activeRequest?.id !== response.requestId) return;
    clearDeadline();
    activeRequest = null;

    if (response.type === "rendered") {
      lastSuccessfulOutput = {
        evaluated: response.evaluated,
        html: response.html,
        reader: response.reader,
      };
      publish({
        diagnostic: null,
        output: lastSuccessfulOutput,
        renderState: "rendered",
        stale: false,
      });
      return;
    }

    fail(response.diagnostic ?? {
      message: "Render failed.",
      phase: "render",
      source: null,
    });
  }

  function handleWorkerError(candidate) {
    if (candidate !== worker) return;
    terminateWorker();
    publish({ workerState: "failed" });
    fail(initializationDiagnostic);
  }

  function spawnWorker() {
    workerReady = false;
    publish({ workerState: "initializing" });
    const candidate = createWorker();
    worker = candidate;
    candidate.addEventListener("message", ({ data }) => handleMessage(candidate, data));
    candidate.addEventListener("error", () => handleWorkerError(candidate));
  }

  function render(source) {
    clearAutoTimer();
    const request = { id: state.requestId + 1, source };
    pendingRequest = request;
    publish({ diagnostic: null, renderState: "waiting", requestId: request.id });

    if (activeRequest) {
      clearDeadline();
      activeRequest = null;
      terminateWorker();
      spawnWorker();
      return request.id;
    }
    if (!worker) spawnWorker();
    sendPendingRequest();
    return request.id;
  }

  return {
    cancelScheduled: clearAutoTimer,
    getState: () => state,
    render,
    schedule(source) {
      clearAutoTimer();
      autoTimer = setTimer(() => {
        autoTimer = null;
        render(source);
      }, autoDelay);
    },
    start() {
      if (started) return;
      started = true;
      spawnWorker();
    },
    stop() {
      clearAutoTimer();
      clearDeadline();
      terminateWorker();
    },
  };
}
