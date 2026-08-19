import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdtemp, readFile } from "node:fs/promises";
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

const SHA256_EMPTY = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

function v2InitialState() {
  return {
    protocol: "rd-agent-state/v2",
    sequence: 0,
    ...identity,
    runtimeType: "PI",
    profileSnapshotId: "snapshot-1",
    currentGoal: "ship verified backend behavior",
    budget: { availability: "UNKNOWN" },
    todos: [{
      todoId: "host-acceptance-1",
      owner: "HOST",
      kind: "ACCEPTANCE",
      title: "acceptance A",
      status: "PENDING",
      required: true,
      acceptanceCriteriaId: "AC-001",
      acceptanceContentHash: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      evidenceArtifactIds: [],
    }],
    facts: [],
  };
}

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
  assert.equal(injected.at(-1), stateMessages[0]);
});

test("v2 injection advances independently and records exact state, prompt, and block provenance", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-inject-v2-"));
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 8192,
    initialState: v2InitialState(),
  });
  await projector.initialize();
  const lifecycleEvents = [];
  const sink = {
    lifecycle: async (eventType, payload) => lifecycleEvents.push({ eventType, payload }),
  };
  const original = [
    { role: "user", content: [{ type: "text", text: "start" }] },
    { role: "custom", customType: STATE_CUSTOM_TYPE, content: [{ type: "text", text: "stale" }] },
    { role: "assistant", content: [{ type: "text", text: "working" }] },
  ];

  const first = injectLatestState(original, projector, sink, { promptHash: SHA256_EMPTY });
  const second = injectLatestState(first, projector, sink, { promptHash: SHA256_EMPTY });
  await new Promise((resolve) => setImmediate(resolve));
  await projector.flush();

  const injections = lifecycleEvents.filter((event) => event.eventType === "STATE_CONTEXT_INJECTED");
  assert.equal(injections.length, 2);
  assert.deepEqual(injections.map((event) => event.payload.injectionSequence), [1, 2]);
  assert.deepEqual(injections.map((event) => event.payload.stateSequence), [0, 0]);
  assert.equal(injections[0].payload.stateHash, injections[1].payload.stateHash);
  assert.match(injections[1].payload.stateHash, /^sha256:[a-f0-9]{64}$/);
  assert.equal(injections[1].payload.promptHash, SHA256_EMPTY);
  assert.match(injections[1].payload.blockHash, /^sha256:[a-f0-9]{64}$/);
  assert.equal(
    injections[1].payload.blockHash,
    `sha256:${createHash("sha256").update(injections[1].payload.injectedBlock, "utf8").digest("hex")}`,
  );
  assert.equal(injections[1].payload.injectedBlock, second.at(-1).content[0].text);
  assert.match(injections[1].payload.injectedAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.match(injections[1].payload.idempotencyKey, /^sha256:[a-f0-9]{64}$/);
  assert.notEqual(injections[0].payload.idempotencyKey, injections[1].payload.idempotencyKey);
  assert.equal(second.filter((message) => message.customType === STATE_CUSTOM_TYPE).length, 1);
  assert.equal(second.at(-1).customType, STATE_CUSTOM_TYPE);
  assert.match(second.at(-1).content[0].text, /injection-sequence="2"/);
  assert.equal(projector.sequence, 0);
  const effective = JSON.parse(await readFile(join(dir, "agent-effective-context-latest.json"), "utf8"));
  assert.equal(effective.protocol, "rd-agent-effective-context/v1");
  assert.equal(effective.stageRunId, identity.stageRunId);
  assert.equal(effective.injectionSequence, 2);
  assert.equal(effective.injectedBlock, second.at(-1).content[0].text);
  assert.deepEqual(effective.compositionOrder, ["PROMPT_SNAPSHOT", "AGENT_STATE_BLOCK"]);
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
