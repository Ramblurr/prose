import { load } from "@starfederation/datastar/bundles/datastar";
import { PluginType } from "@starfederation/datastar/types";
import { readinessState } from "./protocol.js";

const workerStateEvent = "prose-worker-state";

load({
  type: PluginType.Watcher,
  name: "workerStateAdapter",
  onGlobalInit({ signals }) {
    document.addEventListener(workerStateEvent, ({ detail }) => signals.merge(detail));
  },
});

function showWorkerState(workerState, workerStatus, workerStatusDetail) {
  document.dispatchEvent(new CustomEvent(workerStateEvent, {
    detail: {
      workerReady: workerState === "ready",
      workerState,
      workerStatus,
      workerStatusDetail,
    },
  }));
}

const worker = new Worker(new URL("./worker.js", import.meta.url));
worker.addEventListener("message", ({ data }) => {
  const state = readinessState(data);
  if (state === "ready") {
    showWorkerState("ready", "Ready", "The render worker is ready.");
  } else if (state === "failed") {
    worker.terminate();
    showWorkerState(
      "failed",
      "Initialization failed",
      "The worker uses an incompatible protocol version.",
    );
  }
});
worker.addEventListener("error", () => {
  showWorkerState(
    "failed",
    "Initialization failed",
    "The render worker could not initialize.",
  );
});
