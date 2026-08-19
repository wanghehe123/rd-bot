import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  AgentStateProjector,
  boundSnapshotForInjection,
  evaluateAction,
  stateOutputPaths,
} from "../src/agent-state-projector.mjs";

const identity = {
  taskId: "task-1",
  stageRunId: "stage-1",
  role: "CODING_AGENT",
  attemptNo: 1,
};

test("projector writes monotonic sequence and atomic latest snapshot", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-"));
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 8192,
  });
  await projector.initialize();
  const rewrite = await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "bootstrap",
    todos: [{ id: "t1", title: "Implement", status: "IN_PROGRESS" }],
  });
  assert.equal(rewrite.decision, "ACCEPTED");
  assert.equal(projector.sequence, 1);
  await projector.flush();

  const paths = stateOutputPaths(dir);
  const latest = JSON.parse(await readFile(paths.latest, "utf8"));
  assert.equal(latest.sequence, 1);
  assert.equal(latest.todos[0].id, "t1");

  const events = (await readFile(paths.events, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  assert.equal(events.at(-1).payload.decision, "ACCEPTED");
});

test("v2 projector starts from the verified Host snapshot instead of an empty fallback", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-v2-host-"));
  const hostState = {
    protocol: "rd-agent-state/v2",
    sequence: 0,
    ...identity,
    runtimeType: "PI",
    profileSnapshotId: "snapshot-1",
    currentGoal: "implement and verify",
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
  };
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 8192,
    initialState: hostState,
  });

  await projector.initialize();

  assert.equal(projector.snapshot.protocol, "rd-agent-state/v2");
  assert.equal(projector.snapshot.currentGoal, "implement and verify");
  assert.equal(projector.snapshot.todos.length, 1);
  assert.equal(projector.snapshot.todos[0].todoId, "host-acceptance-1");
  assert.match(projector.prepareInjection().text, /protocol="rd-agent-state\/v2"/);
});

test("v2 actions preserve Host obligations, require scoped evidence, and commit atomically", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-v2-actions-"));
  const hostTodo = {
    todoId: "host-acceptance-1",
    owner: "HOST",
    kind: "ACCEPTANCE",
    title: "acceptance A",
    status: "PENDING",
    required: true,
    acceptanceCriteriaId: "AC-001",
    acceptanceContentHash: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    evidenceArtifactIds: [],
  };
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 4096,
    initialState: {
      protocol: "rd-agent-state/v2",
      sequence: 0,
      ...identity,
      runtimeType: "PI",
      profileSnapshotId: "snapshot-1",
      currentGoal: "implement and verify",
      budget: { availability: "UNKNOWN" },
      todos: [hostTodo],
      facts: [],
    },
  });
  await projector.initialize();

  const deleteHost = await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "rewrite-delete-host",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "replace",
    todos: [{ id: "agent-1", title: "work", status: "PENDING" }],
  });
  assert.equal(deleteHost.decision, "REJECTED");
  assert.match(deleteHost.reason, /Host TODO/i);
  assert.equal(projector.sequence, 0);

  const inProgress = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "host-start",
    expectedSequence: 0,
    clientSequence: 2,
    todoId: hostTodo.todoId,
    targetStatus: "IN_PROGRESS",
  });
  assert.equal(inProgress.decision, "ACCEPTED");
  assert.equal(projector.sequence, 1);

  const wrongEvidence = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "host-done-wrong",
    expectedSequence: 1,
    clientSequence: 3,
    todoId: hostTodo.todoId,
    targetStatus: "DONE",
    evidenceRefs: ["artifact:test.log"],
  });
  assert.equal(wrongEvidence.decision, "REJECTED");
  assert.match(wrongEvidence.reason, /AC-001/);
  assert.equal(projector.sequence, 1);

  const secret = await projector.applyAction({
    tool: "rd_record_fact",
    actionId: "secret-fact",
    expectedSequence: 1,
    clientSequence: 4,
    statement: "api_key=sk-abcdefghijklmnop",
    expectedKind: "INFERRED",
  });
  assert.equal(secret.decision, "REJECTED");
  assert.match(secret.reason, /sensitive/i);
  assert.equal(projector.sequence, 1);

  const done = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "host-done",
    expectedSequence: 1,
    clientSequence: 5,
    todoId: hostTodo.todoId,
    targetStatus: "DONE",
    evidenceRefs: ["acceptance:AC-001:artifact:test.log"],
  });
  assert.equal(done.decision, "ACCEPTED");
  assert.equal(projector.sequence, 2);

  const replay = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "host-done",
    expectedSequence: 1,
    clientSequence: 5,
    todoId: hostTodo.todoId,
    targetStatus: "DONE",
    evidenceRefs: ["acceptance:AC-001:artifact:test.log"],
  });
  assert.equal(replay.decision, "ACCEPTED");
  assert.equal(replay.replayed, true);
  assert.equal(projector.sequence, 2);

  const conflict = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "host-done",
    expectedSequence: 2,
    clientSequence: 6,
    todoId: hostTodo.todoId,
    targetStatus: "BLOCKED",
    blockerReason: "conflicting replay",
  });
  assert.equal(conflict.decision, "REJECTED");
  assert.match(conflict.reason, /conflicting replay/i);
  assert.equal(projector.sequence, 2);
});

test("v2 rejects an uninjectable candidate without growing sequence", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-v2-overflow-"));
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 1200,
    initialState: {
      protocol: "rd-agent-state/v2",
      sequence: 0,
      ...identity,
      runtimeType: "PI",
      profileSnapshotId: "snapshot-1",
      currentGoal: "implement",
      budget: { availability: "UNKNOWN" },
      todos: [],
      facts: [],
    },
  });
  await projector.initialize();

  const overflow = await projector.applyAction({
    tool: "rd_record_fact",
    actionId: "huge-fact",
    expectedSequence: 0,
    clientSequence: 1,
    statement: "x".repeat(2_000),
    expectedKind: "INFERRED",
  });

  assert.equal(overflow.decision, "REJECTED");
  assert.match(overflow.reason, /limit|inject/i);
  assert.equal(projector.sequence, 0);
  assert.equal(projector.snapshot.facts.length, 0);
});

test("v2 publishes canonical snapshot projections only after committed state", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-v2-projection-"));
  const projections = [];
  const projector = new AgentStateProjector({
    identity,
    outputPath: dir,
    maxInjectedStateBytes: 4096,
    initialState: {
      protocol: "rd-agent-state/v2",
      sequence: 0,
      ...identity,
      runtimeType: "PI",
      profileSnapshotId: "snapshot-1",
      currentGoal: "implement and verify",
      budget: { availability: "UNKNOWN" },
      todos: [],
      facts: [],
    },
    onSnapshotUpdated: async (payload) => projections.push(payload),
  });
  await projector.initialize();
  const accepted = await projector.applyAction({
    tool: "rd_record_fact",
    actionId: "fact-1",
    expectedSequence: 0,
    clientSequence: 1,
    statement: "focused test passed",
    expectedKind: "OBSERVED",
    sourceArtifactId: "artifact:test.log",
  });
  const rejected = await projector.applyAction({
    tool: "rd_record_fact",
    actionId: "fact-2",
    expectedSequence: 0,
    clientSequence: 2,
    statement: "stale update",
    expectedKind: "INFERRED",
  });

  assert.equal(accepted.decision, "ACCEPTED");
  assert.equal(rejected.decision, "REJECTED");
  assert.deepEqual(projections.map((projection) => projection.stateSequence), [0, 1]);
  assert.match(projections[1].stateHash, /^sha256:[a-f0-9]{64}$/);
  assert.equal(projections[1].snapshot.sequence, 1);
  assert.match(projections[1].projectedAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.match(projections[1].idempotencyKey, /^sha256:[a-f0-9]{64}$/);
});

test("stale expectedSequence is rejected without advancing sequence", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  const rejected = await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "first",
    todos: [{ id: "t1", title: "A", status: "PENDING" }],
  });
  assert.equal(rejected.decision, "ACCEPTED");
  const stale = await projector.applyAction({
    tool: "rd_todo_update_status",
    actionId: "a2",
    expectedSequence: 0,
    clientSequence: 2,
    todoId: "t1",
    targetStatus: "IN_PROGRESS",
  });
  assert.equal(stale.decision, "REJECTED");
  assert.match(stale.reason, /stale expectedSequence/);
  assert.equal(projector.sequence, 1);
});

test("injection fails closed when snapshot exceeds maxInjectedStateBytes", async () => {
  const huge = {
    sequence: 3,
    taskId: "task-1",
    stageRunId: "stage-1",
    role: "CODING_AGENT",
    attemptNo: 1,
    todos: Array.from({ length: 200 }, (_, index) => ({
      id: `t${index}`,
      title: "x".repeat(200),
      status: "IN_PROGRESS",
    })),
    facts: [],
    recentErrors: [],
  };
  const bounded = boundSnapshotForInjection(huge, 512);
  assert.equal(bounded.ok, false);

  const dir = await mkdtemp(join(tmpdir(), "rd-state-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir, maxInjectedStateBytes: 512 });
  await projector.initialize();
  await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "overflow",
    todos: huge.todos,
  });
  assert.throws(() => projector.buildInjectionMessage(), /STATE_INJECTION_BLOCKED/);
});

test("updateFromToolResult advances sequence and generatedAt", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-tool-result-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });
  const beforeSequence = projector.sequence;

  await projector.updateFromToolResult({
    toolName: "bash",
    isError: false,
    fingerprint: "fp-1",
  });
  await projector.flush();

  assert.equal(projector.sequence, beforeSequence + 1);
  assert.equal(projector.snapshot.sequence, projector.sequence);
  assert.match(projector.snapshot.generatedAt, /^\d{4}-\d{2}-\d{2}T/);
  assert.equal(projector.snapshot.toolCounts["bash:success"], 1);

  const paths = stateOutputPaths(dir);
  const latest = JSON.parse(await readFile(paths.latest, "utf8"));
  assert.equal(latest.sequence, projector.sequence);

  const events = (await readFile(paths.events, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  const toolEvent = events.find((event) => event.eventType === "TOOL_RESULT_RECORDED");
  assert.ok(toolEvent);
  assert.equal(toolEvent.sequence, projector.sequence);
  assert.equal(toolEvent.payload.toolName, "bash");
});

test("updateFromToolResult redacts secret-bearing error text before state persistence", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-agent-state-secret-"));
  const projector = new AgentStateProjector({
    identity: { taskId: "task-1", stageRunId: "stage-1", role: "CODING_AGENT", attemptNo: 1 },
    outputPath: dir,
  });
  await projector.initialize();

  await projector.updateFromToolResult({
    toolName: "bash",
    isError: true,
    error: "Authorization: Bearer sk-super-secret-token",
    fingerprint: "fp-1",
  });

  const latest = JSON.parse(await readFile(join(dir, "agent-state-latest.json"), "utf8"));
  assert.equal(latest.recentErrors[0].error.includes("super-secret"), false);
  assert.match(latest.recentErrors[0].error, /\[REDACTED\]/);
});

test("projectTerminalResult writes terminal resultStatus to latest snapshot", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-terminal-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  await projector.applyAction({
    tool: "rd_todo_rewrite",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });
  const beforeSequence = projector.sequence;

  const projected = await projector.projectTerminalResult({ status: "SUCCESS", reason: "" });
  assert.equal(projected.projected, true);
  assert.equal(projected.status, "SUCCESS");
  assert.equal(projector.snapshot.resultStatus, "SUCCESS");
  assert.equal(projector.sequence, beforeSequence + 1);
  await projector.flush();

  const paths = stateOutputPaths(dir);
  const latest = JSON.parse(await readFile(paths.latest, "utf8"));
  assert.equal(latest.resultStatus, "SUCCESS");

  const events = (await readFile(paths.events, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  const terminalEvent = events.find((event) => event.eventType === "STATE_RESULT_PROJECTED");
  assert.ok(terminalEvent);
  assert.equal(terminalEvent.payload.status, "SUCCESS");
  assert.equal(terminalEvent.sequence, projector.sequence);

  const duplicate = await projector.projectTerminalResult({ status: "FAILED" });
  assert.equal(duplicate.projected, false);
  assert.equal(projector.snapshot.resultStatus, "SUCCESS");
});

test("prepareInjection returns hash and bytes without embedding snapshot text in events", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-inject-meta-"));
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

  const injection = projector.prepareInjection();
  assert.equal(injection.sequence, projector.sequence);
  assert.match(injection.hash, /^[a-f0-9]{64}$/);
  assert.ok(injection.bytes > 0);
  assert.match(injection.message.content[0].text, /<rd-agent-state/);

  await projector.recordContextInjected({ hash: injection.hash, bytes: injection.bytes });
  await projector.flush();

  const paths = stateOutputPaths(dir);
  const events = (await readFile(paths.events, "utf8")).trim().split("\n").map((line) => JSON.parse(line));
  const injectedEvent = events.find((event) => event.eventType === "STATE_CONTEXT_INJECTED");
  assert.ok(injectedEvent);
  assert.equal(injectedEvent.payload.sequence, injection.sequence);
  assert.equal(injectedEvent.payload.hash, injection.hash);
  assert.equal(injectedEvent.payload.bytes, injection.bytes);
  assert.equal(Object.hasOwn(injectedEvent.payload, "text"), false);
});

test("evaluateAction enforces DONE evidence and downgrades forged OBSERVED facts", () => {
  const base = evaluateAction({
    sequence: 0,
    todos: [{ id: "t1", title: "A", status: "IN_PROGRESS" }],
    facts: [],
  }, {
    tool: "rd_todo_update_status",
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    todoId: "t1",
    targetStatus: "DONE",
  }, 0);
  assert.equal(base.decision, "REJECTED");

  const fact = evaluateAction({
    sequence: 0,
    todos: [],
    facts: [],
  }, {
    tool: "rd_record_fact",
    actionId: "f1",
    expectedSequence: 0,
    clientSequence: 1,
    statement: "node 22 is installed",
    expectedKind: "OBSERVED",
  }, 0);
  assert.equal(fact.decision, "ACCEPTED");
  assert.equal(fact.snapshot.facts[0].kind, "INFERRED");
});
