import test from "node:test";
import assert from "node:assert/strict";
import { mkdtemp } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { AgentStateProjector } from "../src/agent-state-projector.mjs";
import { createAgentStateTools } from "../src/agent-state-tools.mjs";

const identity = {
  taskId: "task-1",
  stageRunId: "stage-1",
  role: "CODING_AGENT",
  attemptNo: 1,
};

test("state tools are sequential and reject stale expectedSequence", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-tools-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  const [rewriteTool, updateTool] = createAgentStateTools({ projector, sink: null });

  assert.equal(rewriteTool.executionMode, "sequential");
  assert.equal(updateTool.executionMode, "sequential");

  const accepted = await rewriteTool.execute("call-1", {
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });
  assert.equal(accepted.details.decision, "ACCEPTED");
  assert.equal(projector.sequence, 1);

  const stale = await updateTool.execute("call-2", {
    actionId: "a2",
    expectedSequence: 0,
    clientSequence: 2,
    todoId: "t1",
    targetStatus: "DONE",
    evidenceRefs: ["artifact:test.log"],
  });
  assert.equal(stale.details.decision, "REJECTED");
  assert.equal(projector.sequence, 1);
});

test("rd_record_fact downgrades OBSERVED without source", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-tools-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  const [, , recordTool] = createAgentStateTools({ projector, sink: null });
  const response = await recordTool.execute("call-3", {
    actionId: "f1",
    expectedSequence: 0,
    clientSequence: 1,
    statement: "build succeeded",
    expectedKind: "OBSERVED",
  });
  assert.equal(response.details.decision, "ACCEPTED");
  assert.equal(projector.snapshot.facts[0].kind, "INFERRED");
});

test("DONE requires evidence references", async () => {
  const dir = await mkdtemp(join(tmpdir(), "rd-state-tools-"));
  const projector = new AgentStateProjector({ identity, outputPath: dir });
  await projector.initialize();
  const [rewriteTool, updateTool] = createAgentStateTools({ projector, sink: null });
  await rewriteTool.execute("call-4", {
    actionId: "a1",
    expectedSequence: 0,
    clientSequence: 1,
    reason: "plan",
    todos: [{ id: "t1", title: "Ship", status: "IN_PROGRESS" }],
  });
  const missingEvidence = await updateTool.execute("call-5", {
    actionId: "a2",
    expectedSequence: 1,
    clientSequence: 2,
    todoId: "t1",
    targetStatus: "DONE",
    evidenceRefs: [],
  });
  assert.equal(missingEvidence.details.decision, "REJECTED");
  assert.match(missingEvidence.details.reason, /evidence/i);
});
