import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { load } from "@starfederation/datastar/bundles/datastar";
import { PluginType } from "@starfederation/datastar/types";
import { readinessState, renderRequest } from "./protocol.js";

const stateEvent = "prose-playground-state";
const auto = document.querySelector("#auto-render");
const editorParent = document.querySelector("#source-editor");
const preview = document.querySelector("#preview");
const resultNames = {
  preview: "Preview",
  html: "HTML",
  reader: "Reader",
  evaluated: "Evaluated",
};

let autoTimer;
let currentRequestId = 0;
let editor;
let renderPending = true;
let workerReady = false;

load({
  type: PluginType.Watcher,
  name: "playgroundStateAdapter",
  onGlobalInit({ signals }) {
    document.addEventListener(stateEvent, ({ detail }) => signals.merge(detail));
  },
});

function publish(detail) {
  document.dispatchEvent(new CustomEvent(stateEvent, { detail }));
}

function previewDocument(html) {
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'"></head><body>${html}</body></html>`;
}

function showFailure(phase, message) {
  preview.srcdoc = previewDocument("");
  publish({
    diagnosticMessage: message,
    diagnosticPhase: phase,
    evaluatedResult: "",
    htmlResult: "",
    readerResult: "",
    renderStatus: "Render failed",
    workerStatusDetail: `${phase}: ${message}`,
  });
}

function requestRender() {
  clearTimeout(autoTimer);
  if (!workerReady || !editor) {
    renderPending = true;
    return;
  }

  renderPending = false;
  currentRequestId += 1;
  publish({
    diagnosticMessage: "",
    renderStatus: "Rendering…",
    workerStatusDetail: `Rendering request ${currentRequestId}.`,
  });
  worker.postMessage(renderRequest(currentRequestId, editor.state.doc.toString()));
}

function scheduleAutoRender() {
  clearTimeout(autoTimer);
  if (auto.checked) autoTimer = setTimeout(requestRender, 350);
}

const worker = new Worker(new URL("./worker.js", import.meta.url));
worker.addEventListener("message", ({ data }) => {
  const readiness = readinessState(data);
  if (readiness === "ready") {
    workerReady = true;
    publish({
      workerReady: true,
      workerState: "ready",
      workerStatus: "Ready",
      workerStatusDetail: "The render worker is ready.",
    });
    if (renderPending) requestRender();
    return;
  }
  if (readiness === "failed") {
    worker.terminate();
    publish({
      workerState: "failed",
      workerStatus: "Initialization failed",
    });
    showFailure("Initialization", "The worker uses an incompatible protocol version.");
    return;
  }
  if (data?.requestId !== currentRequestId) return;

  if (data.type === "rendered" && data.protocol === 1) {
    preview.srcdoc = previewDocument(data.html);
    publish({
      diagnosticMessage: "",
      evaluatedResult: data.evaluated,
      htmlResult: data.html,
      readerResult: data.reader,
      renderStatus: "Rendered",
      workerStatusDetail: `Rendered request ${data.requestId}.`,
    });
  } else if (data.type === "failed" && data.protocol === 1) {
    const phase = data.diagnostic?.phase ?? "render";
    showFailure(phase, data.diagnostic?.message ?? "Render failed.");
  }
});
worker.addEventListener("error", () => {
  workerReady = false;
  publish({
    workerReady: false,
    workerState: "failed",
    workerStatus: "Initialization failed",
  });
  showFailure("Initialization", "The render worker could not initialize.");
});

function createEditor(source) {
  editor = new EditorView({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ "aria-labelledby": "source-editor-label" }),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) scheduleAutoRender();
      }),
      keymap.of([
        { key: "Mod-Enter", run: () => (requestRender(), true) },
        ...defaultKeymap,
        ...historyKeymap,
      ]),
    ],
    parent: editorParent,
  });
  editorParent.removeAttribute("aria-busy");
  publish({ editorReady: true });
  if (workerReady) requestRender();
}

async function loadDefaultExample() {
  try {
    const response = await fetch(new URL("../examples/01-text-and-code.prose", import.meta.url));
    if (!response.ok) throw new Error(`Example request failed with HTTP ${response.status}.`);
    createEditor(await response.text());
  } catch (error) {
    publish({
      workerState: "failed",
      workerStatus: "Initialization failed",
    });
    showFailure("Initialization", error.message);
  }
}

document.querySelector("#render").addEventListener("click", requestRender);
auto.addEventListener("change", () => {
  clearTimeout(autoTimer);
  if (auto.checked) scheduleAutoRender();
});
for (const radio of document.querySelectorAll('input[name="result-view"]')) {
  radio.addEventListener("change", () => {
    if (radio.checked) {
      publish({ resultHeading: resultNames[radio.value], resultView: radio.value });
    }
  });
}

void loadDefaultExample();
