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
    // PR 来源只有任务 DTO 的 pullRequestUrl，没有独立 prNumber 字段
    taskPr: {
      prUrl: "https://github.com/org/repo/pull/42",
      workBranch: "rd/task-750"
    }
  };

  const viewAttempt1 = buildRoleDeliverables(inputAttempt1);
  // Attempt 1 should only report self-reported test
  assert.equal(viewAttempt1.reportedChecks.some((c) => c.name === "Agent 自报测试" && c.status === "PASSED"), true);
  // Must NOT display Host Verified badge since codingStageRunId doesn't match Attempt 1!
  assert.equal(viewAttempt1.deliverables.some((d) => d.badge === "HOST_VERIFIED"), false);
  // Task PR is marked as scope: TASK; PR number is derived from the URL
  const prLink = viewAttempt1.artifactLinks.find((l) => l.type === "PULL_REQUEST");
  assert.equal(prLink?.scope, "TASK");
  assert.equal(prLink?.name, "PR #42");
  assert.equal(prLink?.targetUrl, "https://github.com/org/repo/pull/42");

  // URL 中提取不到编号时链接仍要渲染，只退回通用标题
  const viewPrWithoutNumber = buildRoleDeliverables({
    ...inputAttempt1,
    taskPr: { prUrl: "https://git.example.com/org/repo/-/changes" }
  });
  const genericPrLink = viewPrWithoutNumber.artifactLinks.find((l) => l.type === "PULL_REQUEST");
  assert.equal(genericPrLink?.name, "Pull Request");
  assert.equal(genericPrLink?.targetUrl, "https://git.example.com/org/repo/-/changes");

  // 没有 pullRequestUrl 时完全不渲染 PR 链接
  const viewPrAbsent = buildRoleDeliverables({ ...inputAttempt1, taskPr: null });
  assert.equal(viewPrAbsent.artifactLinks.some((l) => l.type === "PULL_REQUEST"), false);

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
    // auditedState 记录使用后端 wire 字段：id / text / evidenceRefs（对象数组）
    auditedState: {
      records: [
        {
          id: "AC-001",
          kind: "ACCEPTANCE",
          text: "结算金额",
          status: "COMPLETED",
          blocking: true,
          evidenceRefs: [{ auditRunId: "ar-1", sourceKind: "QA_COMMAND_LOG", uri: "qa-evidence/console/run-1", sha256: "hash-1" }],
          sourceStageRunId: "stage-qa-1",
          blockedReason: ""
        },
        {
          id: "AC-002",
          kind: "ACCEPTANCE",
          text: "优惠券抵扣",
          status: "COMPLETED",
          blocking: true,
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-1",
          blockedReason: ""
        },
        {
          id: "AC-003",
          kind: "ACCEPTANCE",
          text: "超时赔付说明",
          status: "PENDING",
          blocking: true,
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-1",
          blockedReason: ""
        }
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
  assert.equal(view.auditedChecks.some((c) => c.id === "AC-003"), false);
  assert.equal(view.auditedChecks.every((c) => c.status === "COMPLETED"), true);

  // 审计记录按 wire 字段透传：id/text 与 evidenceRefs（uri + sourceKind）
  const auditedFirst = view.auditedChecks.find((c) => c.id === "AC-001");
  assert.equal(auditedFirst?.text, "结算金额");
  assert.equal(auditedFirst?.evidenceRefs.length, 1);
  assert.equal(auditedFirst?.evidenceRefs[0]?.uri, "qa-evidence/console/run-1");
  assert.equal(auditedFirst?.evidenceRefs[0]?.sourceKind, "QA_COMMAND_LOG");

  // AC-003 is flagged as a key gap in Host audited state (id + text)
  assert.equal(view.keyGaps.some((g) => g.includes("AC-003") && g.includes("超时赔付说明") && g.includes("仍待完成")), true);

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

test("buildRoleDeliverables keeps task-head records out of a historical QA attempt conclusion", () => {
  const view = buildRoleDeliverables({
    taskId: "task-750",
    role: "QA_AGENT",
    stage: {
      stageRunId: "stage-qa-1",
      role: "QA_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({ summary: "QA Attempt 1", acceptanceResults: [] })
    },
    auditedState: {
      records: [
        {
          id: "AC-CURRENT",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "当前 Attempt 缺口",
          status: "PENDING",
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-1",
          blockedReason: ""
        },
        {
          id: "AC-OTHER-COMPLETED",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "后续 Attempt 已完成",
          status: "COMPLETED",
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-2",
          blockedReason: ""
        },
        {
          id: "AC-OTHER-PENDING",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "后续 Attempt 阻断",
          status: "PENDING",
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-2",
          blockedReason: ""
        },
        {
          id: "AC-UNKNOWN-PENDING",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "来源未记录的阻断",
          status: "PENDING",
          evidenceRefs: [],
          sourceStageRunId: "",
          blockedReason: ""
        }
      ]
    }
  });

  // 任务最新 head 的已完成记录保留真实来源，但不伪装成 stage-qa-1 的结论。
  assert.equal(view.auditedChecks.some((record) => record.id === "AC-OTHER-COMPLETED"), true);
  assert.equal(view.auditedChecks.find((record) => record.id === "AC-OTHER-COMPLETED")?.sourceStageRunId, "stage-qa-2");
  assert.equal(view.keyGaps.some((gap) => gap.includes("AC-CURRENT")), true);
  assert.equal(view.keyGaps.some((gap) => gap.includes("AC-OTHER-PENDING")), false);
  assert.equal(view.keyGaps.some((gap) => gap.includes("AC-UNKNOWN-PENDING")), false);

  // 异轮及来源未知的 head 阻断单列，供 Host task-head 区域展示。
  assert.deepEqual(
    view.taskHeadGaps.map((record) => [record.id, record.sourceStageRunId]),
    [["AC-OTHER-PENDING", "stage-qa-2"], ["AC-UNKNOWN-PENDING", ""]]
  );
});

test("buildRoleDeliverables routes every blocking non-completed audit status by source", () => {
  const view = buildRoleDeliverables({
    taskId: "task-750",
    role: "QA_AGENT",
    stage: {
      stageRunId: "stage-qa-1",
      role: "QA_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({ summary: "QA Attempt 1", acceptanceResults: [] })
    },
    auditedState: {
      records: [
        {
          id: "AC-BLOCKED",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "当前 Attempt 已阻断",
          status: "BLOCKED",
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-1",
          blockedReason: "命令失败"
        },
        {
          id: "AC-UNTRUSTED",
          kind: "ACCEPTANCE",
          blocking: true,
          text: "后续 Attempt 尚未核验",
          status: "UNTRUSTED",
          evidenceRefs: [],
          sourceStageRunId: "stage-qa-2",
          blockedReason: ""
        }
      ]
    }
  });

  assert.equal(view.keyGaps.some((gap) => gap.includes("AC-BLOCKED") && gap.includes("BLOCKED")), true);
  assert.equal(view.taskHeadGaps.some((record) => record.id === "AC-UNTRUSTED" && record.status === "UNTRUSTED"), true);
});

test("buildRoleDeliverables keeps allDeliverables/allEvidence complete while default views stay bounded", () => {
  // 真实任务形状：所选 QA 阶段 23 条证据，另有跨阶段记录不得进入任一列表
  const evidence = Array.from({ length: 23 }, (_, i) => ({
    artifactId: `qa-evidence/art-${i}`,
    stageRunId: i < 20 ? "stage-qa-1" : "stage-qa-OTHER"
  }));
  const view = buildRoleDeliverables({
    taskId: "task-750",
    role: "QA_AGENT",
    stage: {
      stageRunId: "stage-qa-1",
      role: "QA_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      resultPreview: JSON.stringify({
        summary: "QA 完成",
        acceptanceResults: Array.from({ length: 6 }, (_, i) => ({
          criteria: `AC-00${i + 1}`,
          status: "PASSED"
        }))
      })
    },
    qaEvidence: evidence
  });

  assert.equal(view.totalEvidenceCount, 20);
  assert.equal(view.keyEvidence.length, 5);
  assert.equal(view.allEvidence.length, 20);
  assert.equal(view.hasMoreEvidence, true);
  // 所有列表内的证据都属于所选阶段；跨阶段记录不进入任一列表
  for (const ev of view.allEvidence) {
    assert.equal(ev.stageRunId, "stage-qa-1");
  }
  assert.equal(view.allEvidence.some((ev) => ev.stageRunId === "stage-qa-OTHER"), false);
  assert.equal(view.keyEvidence.every((ev) => view.allEvidence.includes(ev)), true);
  // keyEvidence 是 allEvidence 的前五项兼容视图
  assert.deepEqual(view.keyEvidence.map((ev) => ev.id), view.allEvidence.slice(0, 5).map((ev) => ev.id));

  // 6 项自报检查全部进入完整模型；QA 不再只有一段长执行概述
  assert.equal(view.reportedChecks.length, 6);
  assert.equal(view.allDeliverables.length, 0);
});
