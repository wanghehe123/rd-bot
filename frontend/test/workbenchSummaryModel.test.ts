import assert from "node:assert/strict";
import test from "node:test";
import {
  isPlaceholderResultSummary,
  roleCardSummary,
  summarizeChecks
} from "../src/pages/admin/rdtask/workbenchSummaryModel.ts";

test("summarizeChecks counts PASS/PASSED as passed and keeps every other status as other", () => {
  assert.deepEqual(
    summarizeChecks([{ status: "PASSED" }, { status: "PASS" }, { status: "SKIPPED" }]),
    { total: 3, passed: 2, other: 1 }
  );
  assert.equal(summarizeChecks([]).total, 0);
  // SKIPPED / BLOCKED / 未知值不得统一改名为“失败”
  const mixed = summarizeChecks([
    { status: "BLOCKED" },
    { status: "WEIRD_STATUS" },
    { status: "PASSED" }
  ]);
  assert.equal(mixed.total, 3);
  assert.equal(mixed.passed, 1);
  assert.equal(mixed.other, 2);
  // 小写状态同样按 PASS 计数
  assert.equal(summarizeChecks([{ status: "pass" }]).passed, 1);
});

test("isPlaceholderResultSummary only matches the ROLE result json placeholder", () => {
  assert.equal(isPlaceholderResultSummary("QA_AGENT result json"), true);
  assert.equal(isPlaceholderResultSummary("REQUIREMENT_REVIEWER result json"), true);
  assert.equal(isPlaceholderResultSummary("  CODING_AGENT result json  "), true);
  assert.equal(isPlaceholderResultSummary("QA 验收完成，全部通过"), false);
  assert.equal(isPlaceholderResultSummary(""), false);
  assert.equal(isPlaceholderResultSummary(undefined), false);
});

const baseStage = {
  stageRunId: "stage-x-1",
  role: "REQUIREMENT_REVIEWER",
  status: "SUCCEEDED",
  attemptNo: 1
};

test("roleCardSummary: placeholder resultSummary and unparseable preview degrade honestly", () => {
  assert.equal(
    roleCardSummary({
      taskId: "task-1",
      stage: { ...baseStage, resultSummary: "REQUIREMENT_REVIEWER result json", resultPreview: undefined }
    }),
    "结构化摘要暂不可用"
  );
  // 截断的 JSON 预览解析失败：不得计算完整数量
  assert.equal(
    roleCardSummary({
      taskId: "task-1",
      stage: { ...baseStage, role: "SOLUTION_ARCHITECT", resultPreview: '{"affectedFiles":["a.ts","b.ts"],"implementationSteps":["step1"' }
    }),
    "结构化摘要暂不可用"
  );
  // 纯文本预览同样不算结构化摘要
  assert.equal(
    roleCardSummary({
      taskId: "task-1",
      stage: { ...baseStage, resultPreview: "plain text without json" }
    }),
    "结构化摘要暂不可用"
  );
});

test("roleCardSummary: missing stage reports not created; failed stage without result reports unfinished", () => {
  assert.equal(roleCardSummary({ taskId: "task-1" }), "尚未创建");
  assert.equal(
    roleCardSummary({
      taskId: "task-1",
      stage: { ...baseStage, status: "FAILED_NEEDS_HUMAN", errorMessage: "预算不足" }
    }),
    "执行未完成，暂无产物摘要"
  );
});

test("roleCardSummary: reviewer summarises feasibility, coverage and missing materials", () => {
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      resultSummary: "REQUIREMENT_REVIEWER result json",
      resultPreview: JSON.stringify({
        feasibility: "FEASIBLE_WITH_RISKS",
        missingInformation: ["材料A", "材料B"],
        acceptanceCoverage: ["AC-1", "AC-2", "AC-3"]
      })
    }
  });
  assert.equal(summary, "可行性 通过 · 覆盖 3 项验收 · 待补充 2 项材料");
});

test("roleCardSummary: architect counts files/steps/tests from the parseable preview", () => {
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "SOLUTION_ARCHITECT",
      resultPreview: JSON.stringify({
        affectedFiles: ["a.ts", "b.ts", "c.ts"],
        implementationSteps: ["s1", "s2"],
        testPlan: ["t1"]
      })
    }
  });
  assert.equal(summary, "影响 3 个文件 · 2 步实施 · 1 项测试计划");
});

test("roleCardSummary: coding counts changed files, matching host verification and task PR only", () => {
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "CODING_AGENT",
      stageRunId: "stage-code-1",
      resultPreview: JSON.stringify({ changedFiles: ["a.ts", "b.ts"] })
    },
    // 宿主验证绑定到 Attempt 2 时，Attempt 1 的卡片不得借用
    hostVerification: { status: "SUCCEEDED", codingStageRunId: "stage-code-2" },
    taskPrUrl: "https://github.com/org/repo/pull/40"
  });
  assert.equal(summary, "变更 2 个文件 · PR #40");

  const withOwnVerify = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "CODING_AGENT",
      stageRunId: "stage-code-2",
      resultPreview: JSON.stringify({ changedFiles: ["a.ts"] })
    },
    hostVerification: { status: "SUCCEEDED", codingStageRunId: "stage-code-2" }
  });
  assert.equal(withOwnVerify, "变更 1 个文件 · 宿主验证通过");
});

test("roleCardSummary: QA labels self-reported checks, task audited records and stage evidence separately", () => {
  const evidence = Array.from({ length: 23 }, (_, i) => ({
    artifactId: `art-${i}`,
    stageRunId: i < 20 ? "stage-qa-1" : "stage-qa-2"
  }));
  const records = Array.from({ length: 7 }, (_, i) => ({
    id: `AC-${i}`,
    kind: "ACCEPTANCE",
    text: `t${i}`,
    status: i < 5 ? "COMPLETED" : "PENDING",
    blocking: true,
    evidenceRefs: [],
    sourceStageRunId: "stage-qa-1",
    blockedReason: ""
  }));
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "QA_AGENT",
      stageRunId: "stage-qa-1",
      resultSummary: "QA_AGENT result json",
      resultPreview: JSON.stringify({
        acceptanceResults: [
          { criteria: "AC-1", status: "PASSED" },
          { criteria: "AC-2", status: "PASS" },
          { criteria: "AC-3", status: "PASSED" },
          { criteria: "AC-4", status: "SKIPPED" },
          { criteria: "AC-5", status: "BLOCKED" },
          { criteria: "AC-6", status: "WEIRD" }
        ]
      })
    },
    qaEvidence: evidence,
    auditedRecords: records
  });
  // 三类数量来源分开：6 条自报（3 通过 3 其他）、任务 head 5 条已完成、本阶段 20 条证据
  assert.equal(summary, "自报检查 6 项（通过 3） · 任务审计已完成 5 项 · QA 证据 20 条");
  assert.ok(!summary.includes("6 个 AC"));
  assert.ok(!summary.includes("7 个"));
});

test("roleCardSummary: truncated preview still surfaces task-level audit/evidence counts for QA", () => {
  // 真实任务形状：resultPreview 是 20000 字符截断 JSON，解析失败
  const truncated = '{"acceptanceResults":[{"criteria":"AC-001","status":"PASSED"},';
  const evidence = Array.from({ length: 23 }, (_, i) => ({
    artifactId: `art-${i}`,
    stageRunId: "stage-qa-1"
  }));
  const records = Array.from({ length: 7 }, (_, i) => ({
    id: `AC-${i}`,
    kind: "ACCEPTANCE",
    text: `t${i}`,
    status: "COMPLETED",
    blocking: true,
    evidenceRefs: [],
    sourceStageRunId: "stage-qa-1",
    blockedReason: ""
  }));
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "QA_AGENT",
      stageRunId: "stage-qa-1",
      resultSummary: "QA_AGENT result json",
      resultPreview: truncated
    },
    qaEvidence: evidence,
    auditedRecords: records
  });
  // 不计算自报检查数量（截断预览），但审计与证据计数必须可用
  assert.equal(summary, "任务审计已完成 7 项 · QA 证据 23 条");
  assert.ok(!summary.includes("自报检查"));
});

test("roleCardSummary: truncated preview still surfaces host verification and task PR for coding", () => {
  const summary = roleCardSummary({
    taskId: "task-1",
    stage: {
      ...baseStage,
      role: "CODING_AGENT",
      stageRunId: "stage-code-1",
      resultPreview: '{"changedFiles":["a.ts","b.ts"'
    },
    hostVerification: { status: "SUCCEEDED", codingStageRunId: "stage-code-1" },
    taskPrUrl: "https://github.com/org/repo/pull/40"
  });
  assert.equal(summary, "宿主验证通过 · PR #40");
});
