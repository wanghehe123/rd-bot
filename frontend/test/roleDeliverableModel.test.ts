import assert from "node:assert/strict";
import test from "node:test";
import { buildRoleDeliverables } from "../src/pages/admin/rdtask/roleDeliverableModel.ts";

test("buildRoleDeliverables for REQUIREMENT_REVIEWER extracts feasibility, coverage and gaps", () => {
  const input = {
    taskId: "task-750",
    role: "REQUIREMENT_REVIEWER",
    stage: {
      stageRunId: "stage-rev-1",
      role: "REQUIREMENT_REVIEWER",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({
        summary: "需求评审基本完成，有少量疑问",
        feasibility: "FEASIBLE_WITH_RISKS",
        missingInformation: ["外卖骑手退款接口签名细节"],
        acceptanceCoverage: ["AC-001 已覆盖", "AC-002 已覆盖"],
        budgetEstimate: { estimatedTotalTokens: 50000, confidence: "HIGH" }
      })
    }
  };

  const view = buildRoleDeliverables(input);
  assert.equal(view.role, "REQUIREMENT_REVIEWER");
  assert.equal(view.executionSummary, "需求评审基本完成，有少量疑问");
  assert.equal(view.deliverables.some((d) => d.label === "可行性" && d.value === "FEASIBLE_WITH_RISKS"), true);
  assert.equal(view.deliverables.some((d) => d.label === "验收覆盖"), true);
  assert.equal(view.keyGaps.some((g) => g.includes("外卖骑手退款接口签名细节")), true);
  assert.equal(view.unavailableReason, null);
});

test("buildRoleDeliverables for SOLUTION_ARCHITECT extracts affected files and steps", () => {
  const input = {
    taskId: "task-750",
    role: "SOLUTION_ARCHITECT",
    stage: {
      stageRunId: "stage-arch-1",
      role: "SOLUTION_ARCHITECT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({
        summary: "方案设计就绪",
        affectedFiles: ["server/Payment.ts", "server/Order.ts"],
        implementationSteps: ["新增超时检测任务", "接入结算退款"],
        testPlan: ["单元测试覆盖计算逻辑", "端到端接口测试"],
        acceptanceMapping: [{ criteria: "AC-001", validation: "调用超时接口返回 200" }]
      })
    }
  };

  const view = buildRoleDeliverables(input);
  assert.equal(view.role, "SOLUTION_ARCHITECT");
  assert.equal(view.deliverables.some((d) => d.label === "影响文件" && d.value.includes("Payment.ts")), true);
  assert.equal(view.deliverables.some((d) => d.label === "实施步骤"), true);
  assert.equal(view.deliverables.length <= 3, true);
  assert.equal(view.hasMoreDeliverables, true); // 4 items in total, limited to 3
});

test("buildRoleDeliverables for CODING_AGENT: Attempt 1 reported PASSED does not borrow Attempt 2 Host Verify", () => {
  // Coding Attempt 1
  const inputAttempt1 = {
    taskId: "task-750",
    role: "CODING_AGENT",
    stage: {
      stageRunId: "stage-code-1",
      role: "CODING_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({
        summary: "已完成第一版修改",
        changedFiles: ["Payment.ts"],
        testStatus: "PASSED"
      })
    },
    // Host Verify was executed on Attempt 2, NOT Attempt 1!
    hostVerification: {
      runId: "hv-002",
      codingStageRunId: "stage-code-2",
      status: "SUCCEEDED"
    },
    taskPr: {
      prNumber: 42,
      prUrl: "https://github.com/org/repo/pull/42"
    }
  };

  const viewAttempt1 = buildRoleDeliverables(inputAttempt1);
  // Attempt 1 should only report self-reported test
  assert.equal(viewAttempt1.reportedChecks.some((c) => c.name === "Agent 自报测试" && c.status === "PASSED"), true);
  // Must NOT display Host Verified badge since codingStageRunId doesn't match Attempt 1!
  assert.equal(viewAttempt1.deliverables.some((d) => d.badge === "HOST_VERIFIED"), false);
  // Task PR is marked as scope: TASK
  const prLink = viewAttempt1.artifactLinks.find((l) => l.type === "PULL_REQUEST");
  assert.equal(prLink?.scope, "TASK");
  assert.equal(prLink?.name, "PR #42");

  // Coding Attempt 2 with matching Host Verification
  const inputAttempt2 = {
    taskId: "task-750",
    role: "CODING_AGENT",
    stage: {
      stageRunId: "stage-code-2",
      role: "CODING_AGENT",
      status: "SUCCEEDED",
      attemptNo: 2,
      resultPreview: JSON.stringify({
        summary: "第二版修复",
        changedFiles: ["Payment.ts", "Config.ts"],
        testStatus: "PASSED"
      })
    },
    hostVerification: {
      runId: "hv-002",
      codingStageRunId: "stage-code-2",
      status: "SUCCEEDED"
    }
  };
  const viewAttempt2 = buildRoleDeliverables(inputAttempt2);
  assert.equal(viewAttempt2.deliverables.some((d) => d.badge === "HOST_VERIFIED"), true);
});

test("buildRoleDeliverables for QA_AGENT: distinguishes QA reported checks from Host audited status", () => {
  const input = {
    taskId: "task-750",
    role: "QA_AGENT",
    stage: {
      stageRunId: "stage-qa-1",
      role: "QA_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({
        summary: "QA 验收完成",
        acceptanceResults: [
          { criteriaId: "AC-001", scope: "CURRENT", status: "PASSED", command: "npm test" },
          { criteriaId: "AC-002", scope: "CURRENT", status: "PASSED", command: "npm test" },
          { criteriaId: "AC-003", scope: "CURRENT", status: "PASSED", command: "npm test" }
        ]
      })
    },
    // However, in audited head state, AC-003 is still PENDING!
    auditedState: {
      records: [
        { recordId: "AC-001", title: "结算金额", status: "COMPLETED", blocking: true, evidenceIds: ["ev-1"] },
        { recordId: "AC-002", title: "优惠券抵扣", status: "COMPLETED", blocking: true, evidenceIds: ["ev-2"] },
        { recordId: "AC-003", title: "超时赔付说明", status: "PENDING", blocking: true, evidenceIds: [] }
      ]
    },
    qaEvidence: [
      { artifactId: "QA_COMMAND_LOG", stageRunId: "stage-qa-1" },
      { artifactId: "QA_SCREENSHOT", stageRunId: "stage-qa-1" }
    ]
  };

  const view = buildRoleDeliverables(input);
  // QA reported all 3 passed
  assert.equal(view.reportedChecks.length, 3);
  assert.equal(view.reportedChecks.every((c) => c.status === "PASSED"), true);

  // But auditedChecks only has the 2 that are COMPLETED in auditedState
  assert.equal(view.auditedChecks.length, 2);
  assert.equal(view.auditedChecks.some((c) => c.recordId === "AC-003"), false);

  // AC-003 is flagged as a key gap in Host audited state
  assert.equal(view.keyGaps.some((g) => g.includes("AC-003") && g.includes("仍待完成")), true);

  // Evidence list contains the 2 items matching stage-qa-1
  assert.equal(view.keyEvidence.length, 2);
  assert.equal(view.totalEvidenceCount, 2);

  // 生成的证据链接必须带当前 taskId（task-scoped 路由）
  for (const ev of view.keyEvidence) {
    assert.equal(ev.contentUrl, `/admin/rd-tasks/task-750/qa-evidence/${ev.id}/content`);
  }
});

test("buildRoleDeliverables: QA evidence prefers backend contentUrl and never leaks another taskId", () => {
  const input = {
    taskId: "task-750",
    role: "QA_AGENT",
    stage: {
      stageRunId: "stage-qa-9",
      role: "QA_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1
    },
    qaEvidence: [
      {
        artifactId: "art-manifest",
        stageRunId: "stage-qa-9",
        name: "证据清单",
        type: "QA_EVIDENCE_MANIFEST",
        contentUrl: "/admin/rd-tasks/task-750/qa-evidence/art-manifest/content"
      },
      {
        artifactId: "art-trace",
        stageRunId: "stage-qa-9",
        name: "Playwright trace",
        type: "QA_TRACE"
      }
    ]
  };

  const view = buildRoleDeliverables(input);
  assert.equal(view.totalEvidenceCount, 2);

  const manifest = view.keyEvidence.find((ev) => ev.id === "art-manifest");
  const trace = view.keyEvidence.find((ev) => ev.id === "art-trace");
  // 已有 contentUrl 原样复用，不重新拼接
  assert.equal(manifest?.contentUrl, "/admin/rd-tasks/task-750/qa-evidence/art-manifest/content");
  assert.equal(manifest?.name, "证据清单");
  // 缺 contentUrl 时生成带当前 taskId 的既有路径
  assert.equal(trace?.contentUrl, "/admin/rd-tasks/task-750/qa-evidence/art-trace/content");
});

test("buildRoleDeliverables handles unstructured or missing results with explicit unavailableReason", () => {
  // Empty Attempt
  const emptyView = buildRoleDeliverables({
    taskId: "task-750",
    role: "REQUIREMENT_REVIEWER"
  });
  assert.equal(emptyView.unavailableReason, "当前角色尚未创建执行 Attempt");

  // Unstructured attempt without summary
  const unstructuredView = buildRoleDeliverables({
    taskId: "task-750",
    role: "SOLUTION_ARCHITECT",
    stage: {
      stageRunId: "stage-arch-unstructured",
      role: "SOLUTION_ARCHITECT",
      status: "RUNNING",
      attemptNo: 1,
      resultPreview: "plain text without json structure"
    }
  });
  assert.equal(unstructuredView.unavailableReason, "尚无可展示的结构化产物");
});
