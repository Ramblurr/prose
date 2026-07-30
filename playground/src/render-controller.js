import { currentRenderResponse } from "./protocol.js";

export function renderOutcome(message, currentRequestId) {
  const response = currentRenderResponse(message, currentRequestId);
  if (!response) return null;

  if (response.type === "rendered") {
    return {
      previewHtml: response.html,
      signals: {
        diagnosticMessage: "",
        evaluatedResult: response.evaluated,
        htmlResult: response.html,
        readerResult: response.reader,
        renderStatus: "Rendered",
        workerStatusDetail: `Rendered request ${response.requestId}.`,
      },
    };
  }

  const phase = response.diagnostic?.phase ?? "render";
  const messageText = response.diagnostic?.message ?? "Render failed.";
  return {
    previewHtml: "",
    signals: {
      diagnosticMessage: messageText,
      diagnosticPhase: phase,
      evaluatedResult: "",
      htmlResult: "",
      readerResult: "",
      renderStatus: "Render failed",
      workerStatusDetail: `${phase}: ${messageText}`,
    },
  };
}
