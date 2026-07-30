import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import { load } from "@starfederation/datastar/bundles/datastar";
import { PluginType } from "@starfederation/datastar/types";
import { createRenderController } from "./render-controller.js";

const stateEvent = "prose-playground-state";
const auto = document.querySelector("#auto-render");
const editorParent = document.querySelector("#source-editor");
const preview = document.querySelector("#preview");
const previewShell = document.querySelector("#preview-shell");
const stalePreviewStatus = document.querySelector("#stale-preview-status");
const resultNames = {
  preview: "Preview",
  html: "HTML",
  reader: "Reader",
  evaluated: "Evaluated",
};
const phaseNames = {
  compile: "Compilation",
  initialization: "Initialization",
  "playground-evaluate": "Playground evaluation",
  read: "Reader",
  timeout: "Timeout",
};

let editor;
let previewHtml = null;

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

function previewProjection(html) {
  const template = document.createElement("template");
  template.innerHTML = html;
  for (const link of template.content.querySelectorAll("a, area")) {
    link.removeAttribute("href");
    link.removeAttribute("xlink:href");
  }
  for (const refresh of template.content.querySelectorAll("meta[http-equiv]")) {
    if (refresh.httpEquiv.toLowerCase() === "refresh") refresh.remove();
  }
  return template.innerHTML;
}

function previewDocument(html) {
  return `<!doctype html><html><head><meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; form-action 'none'; base-uri 'none'"></head><body>${previewProjection(html)}</body></html>`;
}

function diagnosticDetail(diagnostic) {
  if (!diagnostic) return "";
  const details = [];
  if (diagnostic.source) details.push(`Source: ${diagnostic.source}`);
  if (diagnostic.position) {
    details.push(`line ${diagnostic.position.line}, column ${diagnostic.position.column}`);
  }
  if (diagnostic.range) {
    details.push(
      `range ${diagnostic.range.startLine}:${diagnostic.range.startColumn}`
      + `–${diagnostic.range.endLine}:${diagnostic.range.endColumn}`,
    );
    details.push(`indexes ${diagnostic.range.startIndex}–${diagnostic.range.endIndex}`);
  }
  if (diagnostic.failedText) details.push(`failed text: ${JSON.stringify(diagnostic.failedText)}`);
  if (diagnostic.expected) details.push(`expected ${diagnostic.expected}`);
  return details.join(" · ");
}

function showControllerState(state) {
  const output = state.output;
  const nextPreviewHtml = output?.html ?? "";
  if (nextPreviewHtml !== previewHtml) {
    previewHtml = nextPreviewHtml;
    preview.srcdoc = previewDocument(nextPreviewHtml);
  }
  previewShell.classList.toggle("stale-preview", state.stale);
  stalePreviewStatus.hidden = !state.stale;

  const workerStatuses = {
    failed: "Initialization failed",
    initializing: "Initializing…",
    ready: "Ready",
  };
  const renderStatuses = {
    failed: "Render failed",
    rendered: "Rendered",
    rendering: "Rendering…",
    waiting: "Waiting to render",
  };
  const diagnostic = state.diagnostic;
  publish({
    diagnosticDetail: diagnosticDetail(diagnostic),
    diagnosticMessage: diagnostic?.message ?? "",
    diagnosticPhase: diagnostic ? (phaseNames[diagnostic.phase] ?? diagnostic.phase) : "",
    evaluatedResult: output?.evaluated ?? "",
    htmlResult: output?.html ?? "",
    readerResult: output?.reader ?? "",
    renderStatus: renderStatuses[state.renderState],
    stalePreview: state.stale,
    workerReady: state.workerState === "ready",
    workerState: state.workerState,
    workerStatus: workerStatuses[state.workerState],
    workerStatusDetail: diagnostic
      ? `${phaseNames[diagnostic.phase] ?? diagnostic.phase}: ${diagnostic.message}`
      : state.workerState === "ready"
        ? "The render worker is ready."
        : "Starting the isolated render worker.",
  });
}

const controller = createRenderController({
  createWorker: () => new Worker(new URL("./worker.js", import.meta.url)),
  onChange: showControllerState,
});

function requestRender() {
  if (editor) controller.render(editor.state.doc.toString());
}

function scheduleAutoRender() {
  if (auto.checked && editor) controller.schedule(editor.state.doc.toString());
}

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
  requestRender();
}

async function loadDefaultExample() {
  try {
    const response = await fetch(new URL("../examples/01-text-and-code.prose", import.meta.url));
    if (!response.ok) throw new Error(`Example request failed with HTTP ${response.status}.`);
    createEditor(await response.text());
  } catch (error) {
    publish({
      diagnosticDetail: "",
      diagnosticMessage: error.message,
      diagnosticPhase: "Initialization",
      renderStatus: "Render failed",
      workerStatus: "Initialization failed",
    });
  }
}

document.querySelector("#render").addEventListener("click", requestRender);
auto.addEventListener("change", () => {
  if (auto.checked) scheduleAutoRender();
  else controller.cancelScheduled();
});
for (const radio of document.querySelectorAll('input[name="result-view"]')) {
  radio.addEventListener("change", () => {
    if (radio.checked) {
      publish({ resultHeading: resultNames[radio.value], resultView: radio.value });
    }
  });
}

controller.start();
void loadDefaultExample();
