import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const workbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskRoleWorkbench.tsx", import.meta.url),
  "utf8"
);
const codingMeaPanel = readFileSync(
  new URL("../src/components/admin/rdtask/CodingMeaPanel.tsx", import.meta.url),
  "utf8"
);
const roleDeliverablesPanel = readFileSync(
  new URL("../src/components/admin/rdtask/RoleDeliverablesPanel.tsx", import.meta.url),
  "utf8"
);
const hostVerificationCard = readFileSync(
  new URL("../src/components/admin/rdtask/HostVerificationCard.tsx", import.meta.url),
  "utf8"
);

test("wires navigation from Coding MEA to matching QA attempt", () => {
  // TaskRoleWorkbench defines handleNavigateToQaAttempt and passes it to RoleDeliverablesPanel
  assert.match(workbench, /handleNavigateToQaAttempt/);
  assert.match(workbench, /onSelectionChange\("QA_AGENT", qaAttemptNo\)/);
  assert.match(workbench, /onNavigateToQaAttempt=\{handleNavigateToQaAttempt\}/);

  // CodingMeaPanel has onNavigateToQaAttempt prop and button trigger
  assert.match(codingMeaPanel, /onNavigateToQaAttempt\?: \(qaAttemptNo: number\) => void/);
  assert.match(codingMeaPanel, /onNavigateToQaAttempt\?\.(\(audit\.qaStage\?\.attemptNo \|\| 1\))/);

  // RoleDeliverablesPanel mounts CodingMeaPanel with onNavigateToQaAttempt
  assert.match(roleDeliverablesPanel, /onNavigateToQaAttempt=\{onNavigateToQaAttempt\}/);
});

test("separates Agent self-reported checks from Host audited checks in deliverables panel", () => {
  assert.match(roleDeliverablesPanel, /Agent 自报/);
  assert.match(roleDeliverablesPanel, /Host 审计/);
  assert.match(roleDeliverablesPanel, /view\.reportedChecks/);
  assert.match(roleDeliverablesPanel, /view\.auditedChecks/);
  assert.match(roleDeliverablesPanel, /Agent 自报执行情况与 Host 审计结论严格隔离/);
});

test("fetches full stage result on-demand and guards preview truncation", () => {
  assert.match(roleDeliverablesPanel, /getCompleteStageResult\(\s*taskId,\s*stage\.stageRunId/);
  assert.match(roleDeliverablesPanel, /stageResultIdentity/);
  assert.match(roleDeliverablesPanel, /token === fullResultTokenRef\.current/);
  assert.match(roleDeliverablesPanel, /stageResult\.source === "ARTIFACT_PREVIEW"/);
  assert.match(roleDeliverablesPanel, /当前结果为预览截断，未生成完整下载文件/);
  assert.match(roleDeliverablesPanel, /stageResult\.source === "FINALIZATION_RESULT"/);
  assert.match(roleDeliverablesPanel, /下载脱敏 JSON/);
});

test("collapses successful Host verification step details while keeping failures directly visible", () => {
  assert.match(hostVerificationCard, /run\.status === "SUCCEEDED"/);
  assert.match(hostVerificationCard, /步骤详情（构建与静态检查均已通过）/);
  assert.match(hostVerificationCard, /StepDetailRow/);
});
