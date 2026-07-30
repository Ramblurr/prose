import { parentPort } from "node:worker_threads";

globalThis.self = globalThis;
self.postMessage = (message) => parentPort.postMessage(message);
parentPort.on("message", (data) => self.onmessage?.({ data }));

await import(new URL("../../dist/assets/worker.js", import.meta.url));
