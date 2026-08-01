import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { AgentStateProjector, STATE_CUSTOM_TYPE } from "../src/agent-state-projector.mjs";
import {
  createDynamicStateExtension,
  injectLatestState,
} from "../src/context-state-injection.mjs";

const identity = {
  taskId: "task-1",
  stageRunId: "stage-1",
  role: "CODING_AGENT",
  attemptNo: 1,
};

test("injectLatestState emits STATE_CONTEXT_INJECTED lifecycle evidence", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-inject-evidence-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir, maxInjectedStateBytes: 8192 });
  await projector.initialize();
  await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });

  const lifecycleEvents = [];
  const sink = {
    lifecycle: async (eventType, payload) => {
      lifecycleEvents.push({ eventType, payload });
    },
  };
  const messages = [{ role: "user", content: [{ type: "text", text: "start" }] }];
  injectLatestState(messages, projector, sink);
  await new Promise((resolve) => setImmediate(resolve));

  const injected = lifecycleEvents.find((event) => event.eventType === "STATE_CONTEXT_INJECTED");
  assert.ok(injected);
  assert.equal(injected.payload.sequence, projector.sequence);
  assert.match(injected.payload.hash, /^[a-f0-9]{64}$/);
  assert.ok(injected.payload.bytes > 0);
  assert.equal(Object.hasOwn(injected.payload, "text"), false);
});

test("injectLatestState keeps only one latest rd-agent-state message", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-inject-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir, maxInjectedStateBytes: 8192 });
  await projector.initialize();
  await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });

  const messages = [
    { role: "user", content: [{ type: "text", text: "start" }] },
    { role: "custom", customType: STATE_CUSTOM_TYPE, content: [{ type: "text", text: "old-state" }] },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
    { role: "custom", customType: STATE_CUSTOM_TYPE, content: [{ type: "text", text: "older-state" }] },
  ];
  const injected = injectLatestState(messages, projector, null);
  const stateMessages = injected.filter((message) => message.customType === STATE_CUSTOM_TYPE);
  assert.equal(stateMessages.length, 1);
  assert.match(stateMessages[0].content[0].text, /<rd-agent-state/);
  assert.match(stateMessages[0].content[0].text, /"sequence":1/);
  assert.equal(stateMessages[0].display, false);
});

test("context hook replaces stale state without adding turns", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-inject-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  const handlers = new Map();
  const extension = createDynamicStateExtension({
    projector,
    stageRunId: identity.stageRunId,
    sink: { lifecycle: async () => {} },
  });
  extension.factory({
    on(eventName, handler) {
      handlers.set(eventName, handler);
    },
  });

  const contextHandler = handlers.get("context");
  assert.ok(contextHandler);
  const event = {
    messages: [
      { role: "user", content: [{ type: "text", text: "hello" }] },
      { role: "custom", customType: STATE_CUSTOM_TYPE, content: [{ type: "text", text: "stale" }] },
    ],
  };
  contextHandler(event);
  const stateMessages = event.messages.filter((message) => message.customType === STATE_CUSTOM_TYPE);
  assert.equal(stateMessages.length, 1);
  assert.notEqual(stateMessages[0].content[0].text, "stale");
});

test("tool_call hook blocks repeated deterministic failures", () => {
  const handlers = new Map();
  const extension = createDynamicStateExtension({
    projector: null,
    stageRunId: identity.stageRunId,
    sink: null,
  });
  extension.factory({
    on(eventName, handler) {
      handlers.set(eventName, handler);
    },
  });
  const toolResult = handlers.get("tool_result");
  const toolCall = handlers.get("tool_call");
  toolResult({
    toolName: "bash",
    input: { command: "npm test" },
    isError: true,
    error: "permission denied",
  });
  assert.throws(
    () => toolCall({ toolName: "bash", input: { command: "npm test" } }),
    /TOOL_BLOCKED/,
  );
});
