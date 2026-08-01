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
