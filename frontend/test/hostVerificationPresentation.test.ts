import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  buildRoleWorkbench,
  REQUIREMENT_ROLE_ORDER,
  BUG_FIX_ROLE_ORDER
} from "../src/pages/admin/rdtask/roleWorkbenchModel.ts";

test("roleWorkbenchModel keeps exactly 4 agent roles and does not make host verification a 5th role", () => {
  assert.deepEqual(
    REQUIREMENT_ROLE_ORDER,
    ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"],
    "REQUIREMENT_ROLE_ORDER must strictly contain only the 4 agent roles"
  );
  assert.deepEqual(
    BUG_FIX_ROLE_ORDER,
    ["BUG_EVIDENCE_COLLECTOR", "BUG_RAG_RETRIEVER", "BUG_ACCEPTANCE_PLANNER", "BUG_CODING_AGENT"],
    "BUG_FIX_ROLE_ORDER must strictly contain only the 4 agent roles"
  );

  const stageRuns = [
    {
      stageRunId: "stage-1",
      taskId: "task-1",
      role: "REQUIREMENT_REVIEWER",
      attemptNo: 1,
      status: "SUCCEEDED",
      createdAt: "2026-08-17T00:00:00Z",
      updatedAt: "2026-08-17T00:00:00Z"
    },
    {
      stageRunId: "stage-2",
      taskId: "task-1",
      role: "SOLUTION_ARCHITECT",
      attemptNo: 1,
      status: "SUCCEEDED",
      createdAt: "2026-08-17T00:01:00Z",
      updatedAt: "2026-08-17T00:01:00Z"
    },
    {
      stageRunId: "stage-3",
      taskId: "task-1",
      role: "CODING_AGENT",
      attemptNo: 1,
      status: "SUCCEEDED",
      createdAt: "2026-08-17T00:02:00Z",
      updatedAt: "2026-08-17T00:02:00Z"
    }
  ];

  // When latest host verification failed, QA_AGENT shows blocker "等待宿主验证通过"
  const failedVerification = {
    status: "FAILED_RETRYABLE",
    errorMessage: "Build compilation error: cannot find module"
  };

  const workbenchWithFailedVerification = buildRoleWorkbench(
    stageRuns,
    [],
    [],
    "REQUIREMENT",
    failedVerification
  );

  assert.equal(workbenchWithFailedVerification.length, 4, "Must produce exactly 4 roles");
  const qaView = workbenchWithFailedVerification.find((r) => r.role === "QA_AGENT");
  assert.ok(qaView, "QA_AGENT role view must exist");
  assert.equal(qaView.status, "PENDING");
  assert.equal(qaView.blocker, "等待宿主验证通过");

  // When latest host verification is running/building, QA_AGENT shows blocker "等待宿主验证完成"
  const buildingVerification = {
    status: "BUILDING"
  };
  const workbenchWithBuilding = buildRoleWorkbench(
    stageRuns,
    [],
    [],
    "REQUIREMENT",
    buildingVerification
  );
  const qaBuildingView = workbenchWithBuilding.find((r) => r.role === "QA_AGENT");
  assert.ok(qaBuildingView);
  assert.equal(qaBuildingView.blocker, "等待宿主验证完成");

  // When latest host verification succeeded, QA_AGENT blocker is empty
  const succeededVerification = {
    status: "SUCCEEDED"
  };
  const workbenchWithSucceeded = buildRoleWorkbench(
    stageRuns,
    [],
    [],
    "REQUIREMENT",
    succeededVerification
  );
  const qaSucceededView = workbenchWithSucceeded.find((r) => r.role === "QA_AGENT");
  assert.ok(qaSucceededView);
  assert.equal(qaSucceededView.blocker, "");
});

test("rdTaskService declares host verification APIs and path contract", async () => {
  const service = await readFile(new URL("../src/services/rdTaskService.ts", import.meta.url), "utf8");

  assert.match(service, /getRdTaskHostVerifications/);
  assert.match(service, /getRdTaskHostVerification/);
  assert.match(service, /hostVerificationContentUrl/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/host-verifications/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/host-verifications\/\$\{runId\}\/evidence\/\$\{artifactId\}\/content/);
  assert.match(service, /export type HostVerificationStatus/);
  assert.match(service, /export type HostVerificationStepName/);
  assert.match(service, /export interface HostVerificationRun/);
  assert.match(service, /export interface HostVerificationList/);
});

test("HostVerificationCard component and detail page integration contract", async () => {
  const [cardSource, detailPageSource] = await Promise.all([
    readFile(new URL("../src/components/admin/rdtask/HostVerificationCard.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url), "utf8")
  ]);

  // Card component structure and disclaimers
  assert.match(cardSource, /宿主验证（编译 \/ 静态检查）/);
  assert.match(cardSource, /Coding 通过后、浏览器 QA 之前由宿主重跑。不是第五个 Agent。/);
  assert.match(cardSource, /廉价返工/);
  assert.match(cardSource, /2/);
  assert.match(cardSource, /SKIPPED_DOCS_ONLY/);
  assert.match(cardSource, /文档变更，已跳过构建与静态检查/);
  assert.match(cardSource, /FAILED_RETRYABLE/);
  assert.match(cardSource, /FAILED_NEEDS_HUMAN/);
  assert.match(cardSource, /PRODUCT_DEFECT/);
  assert.match(cardSource, /ENVIRONMENT/);
  assert.match(cardSource, /QA_INFRASTRUCTURE/);
  assert.match(cardSource, /此环境尚未启用宿主验证/);
  assert.match(cardSource, /hostVerificationContentUrl/);

  // Detail page wiring: must load verifications and render in roles and audit views
  assert.match(detailPageSource, /getRdTaskHostVerifications/);
  assert.match(detailPageSource, /HostVerificationCard/);
  assert.match(detailPageSource, /hostVerifications=\{hostVerifications\}/);
});
