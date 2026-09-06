import assert from "node:assert/strict";
import test from "node:test";

import {
  buildRoleWorkbench,
  canSubmitRequirementTask,
  deriveInjectionBadge,
  deriveSourceLabel,
  evaluateRolePromptsFreshness,
  isEffectiveContextStale,
  isRetryableRequirementTaskStatus,
  projectRoleResult,
  roleStageSignature,
  selectRoleAttempt,
  shortHash,
  sortAgentTodos,
  taskStatusNotice
} from "../src/pages/admin/rdtask/roleWorkbenchModel.ts";

test("keeps failed requirements on the checkpointed recovery path", () => {
  for (const status of ["REJECTED", "FAILED_RETRYABLE", "FAILED_NEEDS_HUMAN"]) {
    assert.equal(isRetryableRequirementTaskStatus(status), true);
    assert.equal(canSubmitRequirementTask({ status, paused: false }), false);
  }
  assert.equal(canSubmitRequirementTask({ status: "CREATED", paused: false }), true);
  assert.equal(canSubmitRequirementTask({ status: "CREATED", paused: true }), false);
  assert.equal(canSubmitRequirementTask({ status: "RECOVERING", paused: false }), false);
  assert.equal(canSubmitRequirementTask({ status: "WAITING_USER_INPUT", paused: false }), false);
  assert.equal(canSubmitRequirementTask({ status: "WAITING_APPROVAL", paused: false }), false);
});

test("does not treat checkpoint-bound retry progress as a blocker", () => {
  assert.deepEqual(
    taskStatusNotice({
      status: "RECOVERING",
      errorMessage: "checkpoint-bound retry: ROLE_EXECUTION:REQUIREMENT_REVIEWER"
    }),
    { kind: "recovery", message: "正在从失败阶段恢复：需求评审" }
  );
  assert.equal(
    taskStatusNotice({
      status: "FAILED_RETRYABLE",
      errorMessage: "first role command is not bound to the applied policy generation: 1"
    }).kind,
    "blocker"
  );
  assert.equal(
    taskStatusNotice({
      status: "EXECUTING",
      errorMessage: "checkpoint-bound retry: ROLE_EXECUTION:REQUIREMENT_REVIEWER"
    }).kind,
    "none"
  );
});

const stage = (
  role: string,
  attemptNo: number,
  status: string,
  overrides: Record<string, unknown> = {}
) => ({
  stageRunId: `${role}-${attemptNo}`,
  role,
  status,
  attemptNo,
  updateTimeEpochMillis: attemptNo * 100,
  errorMessage: "",
  errorCategory: "",
  resultSummary: "",
  resultPreview: "",
  promptArtifactId: "",
  providerAttempts: [],
  ...overrides
});

test("builds the four requirement roles in delivery order and keeps every attempt", () => {
  const roles = buildRoleWorkbench([
    stage("CODING_AGENT", 2, "RUNNING"),
    stage("REQUIREMENT_REVIEWER", 1, "SUCCEEDED"),
    stage("CODING_AGENT", 1, "FAILED_RETRYABLE")
  ], [], []);

  assert.deepEqual(
    roles.map((item) => item.role),
    ["REQUIREMENT_REVIEWER", "SOLUTION_ARCHITECT", "CODING_AGENT", "QA_AGENT"]
  );
  assert.deepEqual(roles[2].attempts.map((item) => item.attemptNo), [2, 1]);
  assert.equal(roles[2].latestStage?.status, "RUNNING");
});

test("defaults to the failed role before a running or merely latest role", () => {
  const roles = buildRoleWorkbench([
    stage("REQUIREMENT_REVIEWER", 1, "SUCCEEDED"),
    stage("SOLUTION_ARCHITECT", 1, "FAILED_NEEDS_HUMAN", {
      errorMessage: "缺少数据库迁移约束"
    }),
    stage("CODING_AGENT", 1, "RUNNING", { updateTimeEpochMillis: 999 })
  ], [], []);

  const selection = selectRoleAttempt(roles, "", undefined);

  assert.equal(selection.role, "SOLUTION_ARCHITECT");
  assert.equal(selection.attemptNo, 1);
  assert.equal(roles[1].blocker, "缺少数据库迁移约束");
});

test("preserves a valid URL role and attempt selection", () => {
  const roles = buildRoleWorkbench([
    stage("CODING_AGENT", 1, "FAILED_RETRYABLE"),
    stage("CODING_AGENT", 2, "RUNNING")
  ], [], []);

  assert.deepEqual(selectRoleAttempt(roles, "CODING_AGENT", 1), {
    role: "CODING_AGENT",
    attemptNo: 1,
    stageRunId: "CODING_AGENT-1"
  });
});

test("ignores a historical failure when the same role latest attempt succeeded", () => {
  const roles = buildRoleWorkbench([
    stage("REQUIREMENT_REVIEWER", 1, "FAILED_NEEDS_HUMAN"),
    stage("REQUIREMENT_REVIEWER", 2, "SUCCEEDED"),
    stage("SOLUTION_ARCHITECT", 1, "RUNNING")
  ], [], []);

  assert.equal(selectRoleAttempt(roles, "", undefined).role, "SOLUTION_ARCHITECT");
});

test("falls back to the selected role latest attempt when a URL attempt is stale", () => {
  const roles = buildRoleWorkbench([
    stage("CODING_AGENT", 1, "FAILED_RETRYABLE"),
    stage("CODING_AGENT", 3, "RUNNING")
  ], [], []);

  assert.deepEqual(selectRoleAttempt(roles, "CODING_AGENT", 99), {
    role: "CODING_AGENT",
    attemptNo: 3,
    stageRunId: "CODING_AGENT-3"
  });
});

test("binds prompt and QA evidence to the exact stage run", () => {
  const roles = buildRoleWorkbench([
    stage("QA_AGENT", 1, "FAILED_NEEDS_HUMAN")
  ], [{
    stageRunId: "QA_AGENT-1",
    role: "QA_AGENT",
    status: "FAILED_NEEDS_HUMAN",
    attemptNo: 1
  }], [
    { artifactId: "qa-1", stageRunId: "QA_AGENT-1" },
    { artifactId: "qa-other", stageRunId: "QA_AGENT-2" }
  ]);

  assert.equal(roles[3].attempts[0].promptStage?.stageRunId, "QA_AGENT-1");
  assert.deepEqual(roles[3].attempts[0].qaEvidenceIds, ["qa-1"]);
  assert.equal(roles[3].evidenceCount, 1);
  assert.equal(roles[3].issueCount, 1);
});

test("does not turn unbound prompt data into a ghost attempt", () => {
  const roles = buildRoleWorkbench([], [{
    stageRunId: "CODING_AGENT-99",
    role: "CODING_AGENT",
    status: "SUCCEEDED",
    attemptNo: 99
  }], []);

  assert.equal(roles[2].attempts.length, 0);
  assert.equal(roles[2].unboundPromptCount, 1);
});

test("counts successful QA evidence separately from role problems", () => {
  const roles = buildRoleWorkbench([
    stage("QA_AGENT", 1, "SUCCEEDED")
  ], [], [
    { artifactId: "qa-1", stageRunId: "QA_AGENT-1" },
    { artifactId: "qa-2", stageRunId: "QA_AGENT-1" }
  ]);

  assert.equal(roles[3].issueCount, 0);
  assert.equal(roles[3].evidenceCount, 2);
});

test("changes the role evidence signature when stage completion or artifacts change", () => {
  const original = roleStageSignature([
    stage("REQUIREMENT_REVIEWER", 1, "RUNNING", {
      promptArtifactId: "prompt-1",
      contextPackageId: "context-1"
    })
  ]);
  const statusOnly = roleStageSignature([
    stage("REQUIREMENT_REVIEWER", 1, "SUCCEEDED", {
      promptArtifactId: "prompt-1",
      contextPackageId: "context-1",
      updateTimeEpochMillis: 999
    })
  ]);
  const updated = roleStageSignature([
    stage("REQUIREMENT_REVIEWER", 1, "SUCCEEDED", {
      promptArtifactId: "prompt-1",
      contextPackageId: "context-1"
    }),
    stage("SOLUTION_ARCHITECT", 1, "PENDING", { promptArtifactId: "prompt-2" })
  ]);
  const resultArchived = roleStageSignature([
    stage("REQUIREMENT_REVIEWER", 1, "SUCCEEDED", {
      promptArtifactId: "prompt-1",
      contextPackageId: "context-1",
      resultArtifactId: "result-1"
    })
  ]);

  assert.notEqual(original, statusOnly);
  assert.notEqual(statusOnly, resultArchived);
  assert.notEqual(original, updated);
});

test("uses the bug-fix role order without leaking requirement roles", () => {
  const roles = buildRoleWorkbench([
    stage("BUG_CODING_AGENT", 1, "PENDING"),
    stage("BUG_EVIDENCE_COLLECTOR", 1, "SUCCEEDED")
  ], [], [], "BUG_FIX");

  assert.deepEqual(
    roles.map((item) => item.role),
    ["BUG_EVIDENCE_COLLECTOR", "BUG_RAG_RETRIEVER", "BUG_ACCEPTANCE_PLANNER", "BUG_CODING_AGENT"]
  );
});

test("projects reviewer missing information and risks as visible role problems", () => {
  const projection = projectRoleResult("REQUIREMENT_REVIEWER", JSON.stringify({
    decision: "NEED_INFO",
    feasibility: "CONDITIONAL",
    summary: "需要补充接口约束",
    missingInformation: ["明确幂等键", "确认回滚方式"],
    risks: ["历史数据兼容风险"],
    acceptanceCoverage: ["缺少失败回归"]
  }));

  assert.equal(projection.headline, "NEED_INFO");
  assert.deepEqual(projection.problems, ["明确幂等键", "确认回滚方式"]);
  assert.deepEqual(projection.risks, ["历史数据兼容风险"]);
  assert.deepEqual(projection.acceptanceGaps, ["缺少失败回归"]);
});

test("keeps successful acceptance coverage and architecture mappings out of role problems", () => {
  const review = projectRoleResult("REQUIREMENT_REVIEWER", JSON.stringify({
    decision: "APPROVED",
    feasibility: "CAN_DO",
    summary: "需求可执行",
    missingInformation: [],
    risks: [],
    acceptanceCoverage: ["桌面与移动端均已覆盖"],
    budgetEstimate: {
      initialTokens: 1000,
      retryReserveTokens: 200,
      estimatedTotalTokens: 1200,
      confidence: "HIGH",
      basis: "历史任务",
      historicalSamples: []
    }
  }));
  const design = projectRoleResult("SOLUTION_ARCHITECT", JSON.stringify({
    summary: "方案可执行",
    affectedFiles: ["TaskRoleWorkbench.tsx"],
    implementationSteps: ["拆分角色视图"],
    acceptanceMapping: [{ criteria: "角色可切换", validation: "浏览器验证" }],
    testPlan: ["npm test"]
  }));

  assert.deepEqual(review.acceptanceGaps, []);
  assert.match(review.facts.find((item) => item.label === "验收覆盖")?.value || "", /桌面与移动端均已覆盖/);
  assert.match(review.facts.find((item) => item.label === "预算估算")?.value || "", /1,200/);
  assert.deepEqual(design.acceptanceGaps, []);
  assert.match(design.facts.find((item) => item.label === "验收映射")?.value || "", /角色可切换/);
  assert.match(design.facts.find((item) => item.label === "验收映射")?.value || "", /浏览器验证/);
});

test("projects only failed QA checks and missing browser validation as problems", () => {
  const projection = projectRoleResult("QA_AGENT", JSON.stringify({
    status: "FAILED",
    summary: "移动端回归失败",
    failureCategory: "BROWSER_REGRESSION",
    browserValidation: { required: true, performed: false, browser: "chromium", viewports: ["390x844"] },
    acceptanceResults: [
      { criteria: "桌面首屏", scope: "CURRENT", status: "PASSED", command: "npm test", exitCode: 0 },
      { criteria: "移动端角色切换", scope: "REGRESSION", status: "FAILED", command: "playwright test", exitCode: 1 }
    ]
  }));

  assert.deepEqual(projection.problems, [
    "移动端角色切换",
    "要求浏览器验证，但当前 attempt 未执行"
  ]);
  assert.equal(projection.failureCategory, "BROWSER_REGRESSION");
  assert.deepEqual(projection.qaChecks.map((item) => [item.scope, item.status]), [
    ["CURRENT", "PASSED"],
    ["REGRESSION", "FAILED"]
  ]);
  assert.deepEqual(projection.browserValidation, {
    required: true,
    performed: false,
    browser: "chromium",
    viewports: ["390x844"]
  });
});

test("keeps two attempts of the same role strictly isolated without leaking state or prompt", () => {
  const roles = buildRoleWorkbench([
    stage("CODING_AGENT", 1, "FAILED_RETRYABLE", {
      agentStateSequence: 5,
      agentStateContentHash: "hash-state-1"
    }),
    stage("CODING_AGENT", 2, "RUNNING", {
      agentStateSequence: 12,
      agentStateContentHash: "hash-state-2"
    })
  ], [
    {
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "FAILED_RETRYABLE",
      attemptNo: 1,
      latestState: {
        available: true,
        sequence: 5,
        contentHash: "hash-state-1"
      } as any
    },
    {
      stageRunId: "CODING_AGENT-2",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 2,
      latestState: {
        available: true,
        sequence: 12,
        contentHash: "hash-state-2"
      } as any
    }
  ], [
    { artifactId: "qa-attempt-1", stageRunId: "CODING_AGENT-1" },
    { artifactId: "qa-attempt-2", stageRunId: "CODING_AGENT-2" }
  ]);

  const codingRole = roles.find((item) => item.role === "CODING_AGENT")!;
  assert.equal(codingRole.attempts.length, 2);

  const attempt2 = codingRole.attempts[0];
  const attempt1 = codingRole.attempts[1];

  assert.equal(attempt2.attemptNo, 2);
  assert.equal(attempt2.promptStage?.latestState?.sequence, 12);
  assert.deepEqual(attempt2.qaEvidenceIds, ["qa-attempt-2"]);

  assert.equal(attempt1.attemptNo, 1);
  assert.equal(attempt1.promptStage?.latestState?.sequence, 5);
  assert.deepEqual(attempt1.qaEvidenceIds, ["qa-attempt-1"]);
});

test("derives injection badges properly and distinguishes latest available vs injected available", () => {
  // Case 1: effectiveContext is available -> 动态状态已注入
  const injected = deriveInjectionBadge(
    { available: true, sequence: 10 } as any,
    { available: true, sequence: 12 } as any,
    "PI"
  );
  assert.equal(injected.label, "动态状态已注入");
  assert.equal(injected.tone, "success");

  // Case 2: effectiveContext unavailable, but latestState available (e.g. latest seq 12, injected seq 10) -> 最新状态未注入
  const uninjected = deriveInjectionBadge(
    { available: false, unavailableReason: "尚无可证明的注入上下文" } as any,
    { available: true, sequence: 12 } as any,
    "PI"
  );
  assert.equal(uninjected.label, "最新状态未注入");
  assert.equal(uninjected.tone, "warning");

  // Case 3: non-PI runtime -> 当前 Attempt 不是 PI，状态栏不适用
  const nonPi = deriveInjectionBadge(
    { available: false } as any,
    { available: false } as any,
    "CLAUDE_CODE"
  );
  assert.equal(nonPi.label, "当前 Attempt 不是 PI，状态栏不适用");
  assert.equal(nonPi.tone, "neutral");

  // Case 4: disabled dynamic state
  const disabled = deriveInjectionBadge(
    { available: false, unavailableReason: "动态状态未启用" } as any,
    undefined,
    "PI"
  );
  assert.equal(disabled.label, "动态状态未启用");
  assert.equal(disabled.tone, "neutral");
});

test("sorts agent todos according to lifecycle priority and preserves stability", () => {
  const todos = [
    { title: "Task D", status: "DONE" },
    { title: "Task B", status: "BLOCKED" },
    { title: "Task C", status: "PENDING" },
    { title: "Task A", status: "IN_PROGRESS" },
    { title: "Task E", status: "CANCELLED" }
  ];

  const sorted = sortAgentTodos(todos);
  assert.deepEqual(
    sorted.map((t) => t.status),
    ["IN_PROGRESS", "BLOCKED", "PENDING", "DONE", "CANCELLED"]
  );
});

test("updates roleStageSignature when state sequence or injection identity changes", () => {
  const base = roleStageSignature([
    stage("CODING_AGENT", 1, "RUNNING", {
      agentStateSequence: 1,
      agentStateContentHash: "hash-state-1",
      agentLastInjectionSequence: 1,
      agentLastInjectedBlockHash: "hash-block-1"
    })
  ]);

  const stateAdvance = roleStageSignature([
    stage("CODING_AGENT", 1, "RUNNING", {
      agentStateSequence: 2,
      agentStateContentHash: "hash-state-2",
      agentLastInjectionSequence: 1,
      agentLastInjectedBlockHash: "hash-block-1"
    })
  ]);

  const injectionOnlyAdvance = roleStageSignature([
    stage("CODING_AGENT", 1, "RUNNING", {
      agentStateSequence: 1,
      agentStateContentHash: "hash-state-1",
      agentLastInjectionSequence: 2,
      agentLastInjectedBlockHash: "hash-block-2"
    })
  ]);

  assert.notEqual(base, stateAdvance);
  assert.notEqual(base, injectionOnlyAdvance);
  assert.notEqual(stateAdvance, injectionOnlyAdvance);
});

test("evaluates role prompt freshness against overview expected identity", () => {
  const expectedMap = {
    "CODING_AGENT-1": {
      stateSequence: 12,
      stateHash: "state-hash-12",
      injectionSequence: 4,
      injectedStateSequence: 3,
      injectedBlockHash: "block-hash-4",
      promptHash: "prompt-hash-1"
    }
  };

  // Case 1: response state sequence is behind expected (10 vs 12) -> STALE_DISCARD
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 10, contentHash: "state-hash-10" } as any,
      effectiveContext: { available: true, injectionSequence: 4, injectedBlockHash: "block-hash-4", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "STALE_DISCARD"
  );

  // Case 2: response injection sequence is behind expected (3 vs 4) -> STALE_DISCARD
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 12, contentHash: "state-hash-12" } as any,
      effectiveContext: { available: true, injectionSequence: 3, injectedBlockHash: "block-hash-3", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "STALE_DISCARD"
  );

  // Case 3: sequence matches but hash differs -> CONSISTENCY_ERROR
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 12, contentHash: "corrupted-hash" } as any,
      effectiveContext: { available: true, injectionSequence: 4, injectedBlockHash: "block-hash-4", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "CONSISTENCY_ERROR"
  );

  // Case 4: sequences and hashes match perfectly -> ACCEPT
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 12, contentHash: "state-hash-12" } as any,
      effectiveContext: { available: true, injectionSequence: 4, injectedBlockHash: "block-hash-4", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "ACCEPT"
  );

  // Case 5: response state sequence is ahead of expected (13 vs 12) -> ACCEPT_AND_RECONCILE
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 13, contentHash: "state-hash-13" } as any,
      effectiveContext: { available: true, injectionSequence: 4, injectedBlockHash: "block-hash-4", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "ACCEPT_AND_RECONCILE"
  );

  // Case 6: response injection sequence is ahead of expected (5 vs 4) -> ACCEPT_AND_RECONCILE
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "CODING_AGENT-1",
      role: "CODING_AGENT",
      status: "RUNNING",
      attemptNo: 1,
      latestState: { available: true, sequence: 12, contentHash: "state-hash-12" } as any,
      effectiveContext: { available: true, injectionSequence: 5, injectedBlockHash: "block-hash-5", promptContentHash: "prompt-hash-1" } as any
    }], expectedMap),
    "ACCEPT_AND_RECONCILE"
  );

  // Case 7: overview still advertises archived v1 state (seq 32) while role-prompts
  // marks latest/effective unavailable (no PI_AGENT_STATE_V2). That is not a stale
  // payload — discarding it hides the bound static Prompt.
  assert.equal(
    evaluateRolePromptsFreshness([{
      stageRunId: "7501657509921427457",
      role: "CODING_AGENT",
      status: "SUCCEEDED",
      attemptNo: 1,
      latestState: {
        available: false,
        sequence: 0,
        contentHash: "",
        unavailableReason: "当前 Attempt 未启用 PI_AGENT_STATE_V2 capability"
      } as any,
      effectiveContext: {
        available: false,
        injectionSequence: 0,
        injectedBlockHash: "",
        promptContentHash: "",
        unavailableReason: "当前 Attempt 未启用 PI_AGENT_STATE_V2 capability"
      } as any
    }], {
      "7501657509921427457": {
        stateSequence: 32,
        stateHash: "sha256:a082830b67e679166b0ec37a839540b0c",
        injectionSequence: 0,
        injectedStateSequence: -1,
        injectedBlockHash: "",
        promptHash: ""
      }
    }),
    "ACCEPT"
  );
});

test("reads stale provenance strictly from backend fields without clock inference", () => {
  assert.equal(isEffectiveContextStale({ stale: true, staleReason: "threshold exceeded" }), true);
  assert.equal(isEffectiveContextStale({ stale: false }), false);
  assert.equal(isEffectiveContextStale(undefined), false);

  assert.equal(deriveSourceLabel("LIVE_PROJECTION", false), "运行中投影");
  assert.equal(deriveSourceLabel("LIVE_PROJECTION", true), "运行中投影（终态）");
  assert.equal(deriveSourceLabel("ARCHIVED_ARTIFACT", true), "已归档终态");
  assert.equal(deriveSourceLabel("", false), "");

  assert.equal(shortHash("7480495920010891264", 8), "74804959");
});

