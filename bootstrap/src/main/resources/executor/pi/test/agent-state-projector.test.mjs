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
