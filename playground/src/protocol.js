export const protocolVersion = 1;

export function readinessState(message) {
  if (message?.type !== "ready") return null;
  return message.protocol === protocolVersion ? "ready" : "failed";
}

export function renderRequest(requestId, source, companionSource = null) {
  return {
    type: "render",
    protocol: protocolVersion,
    requestId,
    program: {
      source,
      companion: companionSource === null ? null : { source: companionSource },
    },
  };
}

export function currentRenderResponse(message, requestId) {
  if (message?.protocol !== protocolVersion || message.requestId !== requestId) return null;
  return message.type === "rendered" || message.type === "failed" ? message : null;
}
