import { defaultKeymap, history, historyKeymap } from "@codemirror/commands";
import { EditorView, keymap, lineNumbers } from "@codemirror/view";
import "@starfederation/datastar/bundles/datastar";
import { createExampleController } from "./example-controller.js";
import { atShorthand } from "./lozenge-shorthand.js";
import { balancedSyntax, clojureLanguage, proseLanguage } from "./prose-language.js";
import { previewDocument } from "./preview-document.js";
import { createRenderController } from "./render-controller.js";

const stateEvent = "prose-playground-state";
const auto = document.querySelector("#auto-render");
const appearanceRadios = [...document.querySelectorAll('input[name="appearance"]')];
const companionEditorParent = document.querySelector("#companion-editor");
const companionEditorSection = document.querySelector("#companion-editor-section");
const editorStack = document.querySelector("#editor-stack");
const exampleSelect = document.querySelector("#example-select");
const emptyResult = document.querySelector("#empty-result");
const preview = document.querySelector("#preview");
const previewShell = document.querySelector("#preview-shell");
const previewTheme = document.querySelector("#preview-theme");
const resultShell = document.querySelector("#result-shell");
const resetExample = document.querySelector("#reset-example");
const sourceEditorParent = document.querySelector("#source-editor");
const sourceHeading = document.querySelector("#source-heading");
const stalePreviewStatus = document.querySelector("#stale-preview-status");
const toggleCompanion = document.querySelector("#toggle-companion");
const exampleUrls = {
  "custom-tag-function": {
    companion: new URL("../examples/playground/example_tags.clj", import.meta.url),
    source: new URL("../examples/03-custom-tag-function.prose", import.meta.url),
  },
  "html-from-a-collection": {
    source: new URL("../examples/04-html-from-a-collection.prose", import.meta.url),
  },
  "semantic-html": {
    source: new URL("../examples/02-semantic-html.prose", import.meta.url),
  },
  "text-and-code": {
    source: new URL("../examples/01-text-and-code.prose", import.meta.url),
  },
};
const exampleDescriptors = [...exampleSelect.options].map((option) => ({
  ...exampleUrls[option.value],
  id: option.value,
  title: option.textContent.trim(),
}));
const phaseNames = {
  "companion-evaluate": "Companion evaluation",
  compile: "Compilation",
  initialization: "Initialization",
  "playground-evaluate": "Playground evaluation",
  read: "Reader",
  timeout: "Timeout",
};

let activating = false;
let companionEditor;
let exampleController;
let previewHtml = null;
let previewProjectionKey = null;
let sourceEditor;

const preferredAppearance = window.matchMedia("(prefers-color-scheme: dark)").matches
  ? "dark"
  : "light";
document.body.dataset.appearance = preferredAppearance;
document.querySelector(`input[name="appearance"][value="${preferredAppearance}"]`).checked = true;

function publish(detail) {
  window.dispatchEvent(new CustomEvent(stateEvent, { detail }));
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

function refreshPreview() {
  const appearance = document.body.dataset.appearance;
  const themeEnabled = previewTheme.checked;
  const nextKey = JSON.stringify([appearance, themeEnabled, previewHtml]);
  if (nextKey === previewProjectionKey) return;
  previewProjectionKey = nextKey;
  preview.srcdoc = previewDocument(previewProjection(previewHtml ?? ""), {
    appearance,
    themeEnabled,
  });
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
  previewHtml = output?.html ?? "";
  refreshPreview();
  const firstError = state.renderState === "failed" && output === null;
  emptyResult.hidden = !firstError;
  resultShell.classList.toggle("empty-result-visible", firstError);
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

function currentProgram() {
  const hasCompanion = exampleController.getState().companion !== null;
  return {
    companion: hasCompanion ? companionEditor.state.doc.toString() : null,
    source: sourceEditor.state.doc.toString(),
  };
}

function requestRender() {
  if (!sourceEditor || !companionEditor) return;
  const program = currentProgram();
  controller.render(program.source, program.companion);
}

function scheduleAutoRender() {
  if (!auto.checked || !sourceEditor || !companionEditor) return;
  const program = currentProgram();
  controller.schedule(program.source, program.companion);
}

function createEditor({ label, language, onEdit, parent, shorthand = false, source }) {
  return new EditorView({
    doc: source,
    extensions: [
      lineNumbers(),
      history(),
      language,
      balancedSyntax,
      ...(shorthand ? atShorthand : []),
      EditorView.lineWrapping,
      EditorView.contentAttributes.of({ "aria-labelledby": label }),
      EditorView.updateListener.of((update) => {
        if (update.docChanged && !activating) {
          onEdit(update.state.doc.toString());
          scheduleAutoRender();
        }
      }),
      keymap.of([
        { key: "Mod-Enter", run: () => (requestRender(), true) },
        ...defaultKeymap,
        ...historyKeymap,
      ]),
    ],
    parent,
  });
}

function companionPresentation({ companion, companionVisible }) {
  const available = companion !== null;
  const visible = available && companionVisible;
  companionEditorSection.hidden = !visible;
  editorStack.classList.toggle("companion-visible", visible);
  toggleCompanion.hidden = !available;
  toggleCompanion.setAttribute("aria-expanded", String(visible));
  toggleCompanion.textContent = visible
    ? "Hide Companion namespace"
    : "Show Companion namespace";
}

function createEditors(program) {
  sourceEditor = createEditor({
    label: "source-editor-label",
    language: proseLanguage,
    onEdit: (source) => exampleController.editSource(source),
    parent: sourceEditorParent,
    source: program.source,
    shorthand: true,
  });
  companionEditor = createEditor({
    label: "companion-editor-label",
    language: clojureLanguage,
    onEdit: (source) => exampleController.editCompanion(source),
    parent: companionEditorParent,
    source: program.companion ?? "",
  });
  sourceEditorParent.removeAttribute("aria-busy");
  companionEditorParent.removeAttribute("aria-busy");
  publish({ editorReady: true });
}

function replaceDocument(editor, source) {
  editor.dispatch({ changes: { from: 0, insert: source, to: editor.state.doc.length } });
}

function activateExample(program) {
  exampleSelect.value = program.selectedExample;
  sourceHeading.textContent = program.title;
  if (!sourceEditor) {
    createEditors(program);
  } else {
    activating = true;
    try {
      replaceDocument(sourceEditor, program.source);
      replaceDocument(companionEditor, program.companion ?? "");
    } finally {
      activating = false;
    }
  }
  companionPresentation(program);
  requestRender();
}

function browserStorage() {
  try {
    return window.localStorage;
  } catch {
    return null;
  }
}

async function exampleText(url) {
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Example request failed with HTTP ${response.status}.`);
  return response.text();
}

async function loadExamples() {
  try {
    const examples = await Promise.all(exampleDescriptors.map(async (descriptor) => ({
      ...descriptor,
      companion: descriptor.companion ? await exampleText(descriptor.companion) : null,
      source: await exampleText(descriptor.source),
    })));
    exampleController = createExampleController({
      examples,
      onActivate: activateExample,
      storage: browserStorage(),
    });
    exampleController.start();
    exampleSelect.disabled = false;
    resetExample.disabled = false;
  } catch (error) {
    publish({
      diagnosticDetail: "",
      diagnosticMessage: error.message,
      diagnosticPhase: "Initialization",
      renderStatus: "Render failed",
      workerStatus: "Initialization failed",
    });
    emptyResult.hidden = false;
    resultShell.classList.add("empty-result-visible");
  }
}

exampleSelect.addEventListener("change", () => exampleController.select(exampleSelect.value));
resetExample.addEventListener("click", () => exampleController.reset());
toggleCompanion.addEventListener("click", () => {
  const state = exampleController.getState();
  companionPresentation(exampleController.setCompanionVisible(!state.companionVisible));
});
document.querySelector("#render").addEventListener("click", requestRender);
auto.addEventListener("change", () => {
  if (auto.checked) scheduleAutoRender();
  else controller.cancelScheduled();
});
for (const radio of document.querySelectorAll('input[name="result-view"]')) {
  radio.addEventListener("change", () => {
    if (radio.checked) publish({ resultView: radio.value });
  });
}
for (const radio of appearanceRadios) {
  radio.addEventListener("change", () => {
    if (!radio.checked) return;
    document.body.dataset.appearance = radio.value;
    refreshPreview();
  });
}
previewTheme.addEventListener("change", refreshPreview);

controller.start();
void loadExamples();
