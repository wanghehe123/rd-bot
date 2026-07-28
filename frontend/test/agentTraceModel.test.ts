import assert from "node:assert/strict";
import test from "node:test";

import {
  appendAgentTraceEvents,
  buildAgentTraceSteps,
  type AgentTraceState
} from "../src/components/admin/rdtask/agentTraceModel.ts";
import type { AgentRuntimeEvent } from "../src/services/executionTraceService.ts";

function event(
  sequence: number,
  eventType: string,
  payload: Record<string, unknown> = {}
): AgentRuntimeEvent {
  return {
    protocol: "rd-agent-event/v1",
    eventType,
    sequence,
    sourceSequence: sequence,
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "QA_AGENT",
    runtimeType: "PI",
    snapshotId: "snapshot-1",
    provider: "longcat-anthropic",
    model: "LongCat-2.0",
    occurredAt: `2026-07-27T04:24:${String(sequence).padStart(2, "0")}.000Z`,
    payload,
    redacted: true
  };
}

test("coalesces consecutive assistant deltas into one readable process step", () => {
  const state = appendAgentTraceEvents(emptyState(), [
    event(1, "TURN_STARTED"),
    event(2, "ASSISTANT_TEXT_DELTA", { delta: "I found the cache directory. " }),
    event(3, "ASSISTANT_TEXT_DELTA", { delta: "Next I will inspect its size." }),
    event(4, "ASSISTANT_TEXT_COMPLETED"),
    event(5, "TURN_COMPLETED")
  ]);

  const steps = buildAgentTraceSteps(state);
  assert.equal(steps.length, 1);
  assert.deepEqual(steps[0], {
    kind: "NARRATIVE",
    id: "narrative-2",
    firstSequence: 2,
    lastSequence: 4,
    startedAt: "2026-07-27T04:24:02.000Z",
    completedAt: "2026-07-27T04:24:04.000Z",
    markdown: "I found the cache directory. Next I will inspect its size.",
    streaming: false
  });
});

test("keeps one tool step while progress events update the same tool call", () => {
  const state = appendAgentTraceEvents(emptyState(), [
    event(1, "TOOL_STARTED", {
      toolCallId: "call-cache-size",
      toolName: "bash",
      displaySummary: "du -sh ~/Library/Containers"
    }),
    event(2, "TOOL_PROGRESS", {
      toolCallId: "call-cache-size",
      toolName: "bash",
      outputPreview: "Scanning files..."
    }),
    event(3, "TOOL_COMPLETED", {
      toolCallId: "call-cache-size",
      toolName: "bash",
      outputPreview: "20G ~/Library/Containers",
      isError: false
    })
  ]);

  const steps = buildAgentTraceSteps(state);
  assert.equal(steps.length, 1);
  assert.deepEqual(steps[0], {
    kind: "TOOL",
    id: "tool-call-cache-size",
    firstSequence: 1,
    lastSequence: 3,
    toolCallId: "call-cache-size",
    toolName: "bash",
    displaySummary: "du -sh ~/Library/Containers",
    outputPreview: "20G ~/Library/Containers",
    status: "SUCCEEDED",
    startedAt: "2026-07-27T04:24:01.000Z",
    completedAt: "2026-07-27T04:24:03.000Z"
  });
});

test("retains failures while hiding low-value transport diagnostics from the process view", () => {
  const state = appendAgentTraceEvents(emptyState(), [
    event(1, "PROVIDER_REQUESTED"),
    event(2, "PROVIDER_RESPONDED", { status: "200" }),
    event(3, "PROTOCOL_ERROR", { error: "invalid runtime event" }),
    event(4, "RESULT_REJECTED", { reason: "missing evidence" })
  ]);

  const steps = buildAgentTraceSteps(state);
  assert.deepEqual(steps.map((step) => step.kind), ["SYSTEM", "RESULT"]);
  assert.match(steps[0].summary, /invalid runtime event/);
  assert.match(steps[1].summary, /missing evidence/);
});

test("does not expose raw tool progress deltas as terminal output", () => {
  const state = appendAgentTraceEvents(emptyState(), [
    event(1, "TOOL_STARTED", {
      toolCallId: "call-safe-preview",
      toolName: "bash",
      displaySummary: "git status --short"
    }),
    event(2, "TOOL_PROGRESS", {
      toolCallId: "call-safe-preview",
      toolName: "bash",
      delta: "Authorization: Bearer private-token"
    })
  ]);

  const [step] = buildAgentTraceSteps(state);
  assert.equal(step?.kind, "TOOL");
  assert.equal(step?.outputPreview, undefined);
});

test("does not append duplicate sequences after SSE replay", () => {
  const first = event(1, "ASSISTANT_TEXT_DELTA", { delta: "Already received." });
  const state = appendAgentTraceEvents(emptyState(), [first]);
  const replayed = appendAgentTraceEvents(state, [first, event(2, "ASSISTANT_TEXT_COMPLETED")]);

  assert.equal(replayed.events.length, 2);
  assert.equal(buildAgentTraceSteps(replayed)[0].markdown, "Already received.");
});

function emptyState(): AgentTraceState {
  return { events: [], seenSequences: [] };
}
