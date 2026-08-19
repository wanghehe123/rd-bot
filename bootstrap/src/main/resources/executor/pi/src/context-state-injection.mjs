import { STATE_CUSTOM_TYPE } from "./agent-state-projector.mjs";
import { createHash } from "node:crypto";
import {
  ToolRetryGuard,
  argsHash,
  classifyError,
  errorFingerprint,
} from "./tool-fingerprint.mjs";

export function createDynamicStateExtension({
  projector,
  retryGuard,
  sink,
  stageRunId,
  promptHash = null,
}) {
  const guard = retryGuard ?? new ToolRetryGuard({ stageRunId });
  return {
    name: "rd-dynamic-state",
    hidden: true,
    projector,
    retryGuard: guard,
    factory(pi) {
      pi.on("context", (event) => {
        if (!Array.isArray(event?.messages)) return;
        event.messages = injectLatestState(event.messages, projector, sink, { promptHash });
      });
      pi.on("tool_call", (event) => {
        const toolName = event?.toolName ?? "";
        if (toolName.startsWith("rd_")) return;
        const blocked = guard.checkBeforeCall({
          toolName,
          args: event?.input ?? {},
        });
        if (blocked.blocked) {
          throw new Error(blocked.reason);
        }
      });
      pi.on("tool_result", async (event) => {
        const toolName = event?.toolName ?? "";
        if (toolName.startsWith("rd_")) return;
        const isError = Boolean(event?.isError ?? event?.error);
        const record = guard.recordResult({
          toolName,
          args: event?.input ?? {},
          error: event?.error ?? event?.result?.error ?? "tool failed",
          isError,
        });
        if (record && sink?.lifecycle) {
          await sink.lifecycle("TOOL_FINGERPRINT_RECORDED", {
            toolName,
            argsHash: record.argsHash,
            category: record.category,
            fingerprint: record.fingerprint,
            count: record.count,
          });
        }
        if (projector) {
          await projector.updateFromToolResult({
            toolName,
            isError,
            error: event?.error ?? event?.result?.error ?? "",
            fingerprint: record?.fingerprint,
          });
        }
      });
    },
  };
}

export function injectLatestState(messages, projector, sink = null, { promptHash = null } = {}) {
  const next = (messages ?? []).filter((message) => message?.customType !== STATE_CUSTOM_TYPE);
  if (!projector) return next;
  try {
    const injection = projector.prepareInjection({ promptHash });
    next.push(injection.message);
    if (sink?.lifecycle) {
      const payload = injection.message.content[0].text.includes('protocol="rd-agent-state/v2"') ? {
        injectionSequence: injection.injectionSequence,
        stateSequence: injection.stateSequence,
        stateHash: injection.stateHash,
        promptHash: injection.promptHash,
        blockHash: injection.blockHash,
        injectedBlock: injection.injectedBlock,
        injectedAt: injection.injectedAt,
        idempotencyKey: injection.idempotencyKey,
        bytes: injection.bytes,
      } : {
        sequence: injection.sequence,
        hash: injection.hash,
        bytes: injection.bytes,
      };
      void safeLifecycle(sink, "STATE_CONTEXT_INJECTED", payload);
    }
    void projector.recordContextInjected(injection);
  } catch (error) {
    next.push({
      role: "custom",
      customType: STATE_CUSTOM_TYPE,
      display: false,
      content: [{
        type: "text",
        text: `<rd-agent-state blocked="true">${String(error.message ?? error)}</rd-agent-state>`,
      }],
    });
  }
  return next;
}

export function hashPrompt(prompt) {
  return `sha256:${createHash("sha256").update(String(prompt ?? ""), "utf8").digest("hex")}`;
}

async function safeLifecycle(sink, eventType, payload) {
  try {
    await sink.lifecycle(eventType, payload);
  } catch {
    // observability must not break context injection
  }
}

export function createFingerprintHelpers(stageRunId) {
  return {
    argsHash: (toolName, args) => argsHash(toolName, args, { stageRunId }),
    classifyError,
    errorFingerprint: (input) => errorFingerprint({ stageRunId, ...input }),
  };
}
