import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const workbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
  "utf8"
);

test("renders a role-first workspace with three focused views", () => {
  assert.match(workbench, /角色工作台/);
  assert.match(workbench, /结果与问题/);
  assert.match(workbench, /输入与证据/);
  assert.match(workbench, /运行记录/);
  assert.match(workbench, /buildRoleWorkbench/);
  assert.match(workbench, /selectRoleAttempt/);
  assert.match(workbench, /TabsList/);
});

test("keeps all four roles reachable without a mobile wide table", () => {
  assert.match(workbench, /grid-cols-2/);
  assert.match(workbench, /xl:grid-cols-4/);
  assert.doesNotMatch(workbench, /min-w-\[(820|900|1080)px\]/);
});

test("binds QA and recovery data to the selected stage run", () => {
  assert.match(workbench, /item\.stageRunId === selectedAttempt\.stageRunId/);
  assert.match(workbench, /failedStageRunId === selectedAttempt\.stageRunId/);
});

test("shows QA evidence load failures instead of presenting a false empty state", () => {
  assert.match(workbench, /evidenceLoading/);
  assert.match(workbench, /qaEvidenceError/);
  assert.match(workbench, /PanelError message=\{qaEvidenceError\}/);
  assert.match(workbench, /!evidenceLoading && !qaEvidenceError/);
  assert.match(workbench, /!evidenceLoading && !retrievalError/);
});

test("does not surface unrelated recovery loading or errors inside another attempt", () => {
  assert.match(workbench, /recoveryMatchesSelectedAttempt/);
  assert.match(workbench, /recoveryExpectedForSelectedAttempt/);
  assert.match(workbench, /unresolvedRagTargetsSelectedAttempt/);
  assert.match(workbench, /isRetryableRequirementTaskStatus\(task\.status\)/);
});

test("shows immutable runtime snapshots and keeps observation one-way", () => {
  assert.match(workbench, /RuntimeExecutionProfilePanel/);
  assert.match(workbench, /getAgentRuntimeSnapshot/);
  assert.match(workbench, /new EventSource/);
  assert.match(workbench, /轮询降级/);
  assert.match(workbench, /没有.*command|运行时事件/);
  assert.doesNotMatch(workbench, /stdin/);
});

test("projects runtime events into the readable trace instead of rendering every delta row", () => {
  assert.match(workbench, /ReadableAgentTrace/);
  assert.doesNotMatch(workbench, /trace\.events\.slice\(-120\)/);
});

test("loads the retained trace before attaching live updates so the newest record is the default view", () => {
  assert.match(workbench, /poll\(\{ latest: true \}\)/);
  assert.match(workbench, /while \(next\?\.hasMore && next\.source !== "ARCHIVED"/);
  assert.match(workbench, /await poll\(\)/);
});
