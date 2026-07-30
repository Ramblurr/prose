export const protocolVersion = 1;

export function readinessState(message) {
  if (message?.type !== "ready") return null;
  return message.protocol === protocolVersion ? "ready" : "failed";
}

export function renderRequest(requestId, source) {
  return {
    type: "render",
    protocol: protocolVersion,
    requestId,
    program: {
      source,
      companion: null,
    },
  };
}
