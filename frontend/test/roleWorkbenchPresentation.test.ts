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
