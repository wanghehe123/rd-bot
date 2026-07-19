import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);
const workbench = readFileSync(
  new URL("../src/components/admin/rdtask/TaskFailureRecoveryWorkbench.tsx", import.meta.url),
  "utf8"
);
const retryService = readFileSync(new URL("../src/services/taskRetryService.ts", import.meta.url), "utf8");

test("renders a structured failure recovery workbench instead of exposing raw stage JSON by default", () => {
  assert.match(detailPage, /TaskFailureRecoveryWorkbench/);
  assert.match(workbench, /失败诊断与恢复/);
  assert.match(workbench, /diagnostic\.issues/);
  assert.match(workbench, /diagnostic\.risks/);
  assert.match(workbench, /diagnostic\.acceptanceGaps/);
  assert.match(workbench, /<details/);
  assert.match(workbench, /原始角色结果/);
});

test("submits operator notes and task-owned evidence to a durable retry checkpoint", () => {
  assert.match(retryService, /failure-recovery/);
  assert.match(retryService, /expectedFailedStageRunId/);
  assert.match(retryService, /expectedFailedRetrievalRunId/);
  assert.match(retryService, /expectedFailedAiReviewRunId/);
  assert.match(retryService, /expectedSourceTaskVersion/);
  assert.match(retryService, /evidenceMaterialIds/);
  assert.match(workbench, /addTextTaskMaterial/);
  assert.match(workbench, /uploadTaskMaterial/);
  assert.match(workbench, /recoveryStageRunId/);
  assert.match(workbench, /retryTaskFromFailure/);
  assert.match(workbench, /expectedFailedRetrievalRunId: snapshot\.retryPoint\.failedRetrievalRunId/);
  assert.match(workbench, /expectedFailedAiReviewRunId: snapshot\.retryPoint\.failedAiReviewRunId/);
  assert.match(workbench, /expectedSourceTaskVersion: snapshot\.retryPoint\.sourceTaskVersion/);
});

test("guards every recovery mutation against task route changes", () => {
  assert.match(workbench, /captureTaskActionGuard/);
  assert.ok((workbench.match(/actionGuard\.isCurrent\(\)/g) || []).length >= 9);
  assert.match(detailPage, /captureTaskActionGuard/);
});

test("uses a real keyboard-accessible button to open the recovery file picker", () => {
  assert.match(workbench, /fileInputRef\.current\?\.click\(\)/);
  assert.doesNotMatch(workbench, /<Button asChild[\s\S]{0,240}<label/);
});

test("renders recovery history without a forced mobile-width table", () => {
  assert.doesNotMatch(workbench, /min-w-\[560px\]/);
  assert.match(workbench, /恢复记录/);
});
