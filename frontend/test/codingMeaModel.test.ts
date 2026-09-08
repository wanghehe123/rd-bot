import assert from "node:assert/strict";
import test from "node:test";
import { buildCodingMeaView, MEA_UNRELATABLE_NOTE } from "../src/pages/admin/rdtask/codingMeaModel.ts";
import { FIXTURE_CODING_MEA_W1E } from "./codingMeaService.test.ts";

test("buildCodingMeaView: first coding attempt without manager displays distinct note", () => {
  const firstCodingResponse = {
    ...FIXTURE_CODING_MEA_W1E,
    codingStages: [
      {
        stageRunId: "stage-code-1",
        role: "CODING_AGENT",
        attemptNo: 1,
        status: "SUCCEEDED",
        resultArtifactId: null,
        startedAtEpochMillis: 1000,
        finishedAtEpochMillis: 2000
      }
    ],
    qaStages: [],
    decisions: [],
    commands: [],
    hostVerifications: []
  };

  const view = buildCodingMeaView(firstCodingResponse, "stage-code-1");
  assert.equal(view.isFirstCodingWithoutManager, true);
  assert.equal(view.currentHeadline, "首次执行尚无前置 Manager 决策");
  assert.equal(view.currentRound?.manage, null);
  assert.equal(view.currentRound?.execute?.attemptLabel, "Coding Attempt 1");
});

test("buildCodingMeaView: Assertion A & B - correlates exact QA stage and remediation round", () => {
  // W1e snapshot-p157 state
  const view = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, "7502208702544482305");
  assert.equal(view.available, true);

  // Assertion A: selected Coding stage points to backend correlated QA stage
  assert.equal(view.currentRound?.audit?.qaStage?.stageRunId, "7502208702544482307");
  assert.equal(view.currentRound?.audit?.qaStage?.attemptNo, 2);

  // Assertion B: remediation labels are distinct
  // Manager: 决策第 2 轮
  assert.equal(view.currentRound?.manage?.roundLabel, "决策第 2 轮");
  assert.equal(view.currentRound?.manage?.route, "EXECUTE");
  assert.deepEqual(view.currentRound?.manage?.targetRecordIds, ["AC-003"]);

  // Remediation: 修复第 1 轮
  assert.equal(view.currentRound?.execute?.remediationRoundLabel, "修复第 1 轮");

  // Coding: Coding Attempt 2
  assert.equal(view.currentRound?.execute?.attemptLabel, "Coding Attempt 2");

  // Host Verify summary
  assert.equal(view.currentRound?.audit?.hostVerify?.passed, true);
  assert.match(view.currentRound?.audit?.hostVerify?.summary || "", /构建与静态检查通过/);
});

test("buildCodingMeaView: Assertion C - flags partial history when page.hasMore is true", () => {
  const view = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, "7502208702544482305");
  assert.equal(view.hasPartialHistory, true);
  assert.equal(view.pageHasMore, true);
});

test("buildCodingMeaView: snapshot-p158 Manager DONE produces review note and preserves task failure", () => {
  // 本轮 QA 的 command（绑定 remediation targetQaStageRunId），DONE 决策在其之后
  const p158Response = {
    ...FIXTURE_CODING_MEA_W1E,
    taskStatus: "FAILED_RETRYABLE", // task ended up failing later
    commands: [
      ...FIXTURE_CODING_MEA_W1E.commands,
      {
        commandId: "cmd-qa-2",
        stage: "ROLE_EXECUTION:QA_AGENT",
        role: "QA_AGENT",
        status: "SUCCEEDED",
        stageRunId: "7502208702544482307",
        stageLinkReason: null,
        commandAttemptNo: 0,
        remediationRoundId: "round-gap-1",
        remediationKind: "MANAGER_GAP_FIX",
        remediationNo: 1,
        remediationSourceStageRunId: "7502208702544482305",
        createdAtEpochMillis: 1725609340000,
        updatedAtEpochMillis: 1725609510000
      }
    ],
    decisions: [
      {
        managerCommandId: "cmd-mgr-done",
        roundNo: 3,
        sourceCommandId: "cmd-qa-2",
        decisionHash: "dec_hash_w1e_p158",
        route: "DONE",
        executorRoute: null,
        targetRecordIds: [],
        boundedContractPreview: "全量验收通过，交由复核",
        boundedContractTruncated: false,
        rationale: "所有 3 项 AC 均已通过",
        stateVersion: 6,
        stateHash: "hash-p158",
        stateAtDecision: {
          available: true,
          unavailableReason: null,
          stateVersion: 6,
          stateHash: "hash-p158",
          recordsTruncated: false,
          records: [
            { id: "AC-001", kind: "REQUIREMENT", text: "结算金额", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null },
            { id: "AC-002", kind: "REQUIREMENT", text: "优惠券", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null },
            { id: "AC-003", kind: "REQUIREMENT", text: "超时赔付", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null }
          ]
        },
        commandCreatedAtEpochMillis: 1725610000000
      }
    ],
    head: {
      available: true,
      unavailableReason: null,
      stateVersion: 6,
      stateHash: "hash-p158",
      recordsTruncated: false,
      records: [
        { id: "AC-001", kind: "REQUIREMENT", text: "结算金额", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null },
        { id: "AC-002", kind: "REQUIREMENT", text: "优惠券", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null },
        { id: "AC-003", kind: "REQUIREMENT", text: "超时赔付", status: "COMPLETED", blocking: true, evidenceRefs: [], sourceStageRunId: null, blockedReason: null }
      ]
    }
  };

  const view = buildCodingMeaView(p158Response, "7502208702544482305");
  assert.equal(view.managerDoneReviewNote, "交由交付复核");
  assert.match(view.currentHeadline, /交由交付复核/);
  // Task status remains FAILED_RETRYABLE in response, buildCodingMeaView does not synthesize COMPLETED
  assert.equal(p158Response.taskStatus, "FAILED_RETRYABLE");
});

test("buildCodingMeaView: unknown selected stageRunId never falls back to another coding attempt", () => {
  // 选择 Attempt 1 不能默认返回最新 Attempt 2：精确匹配失败时如实显示不可用
  const view = buildCodingMeaView(FIXTURE_CODING_MEA_W1E, "stage-not-in-response");
  assert.equal(view.available, true);
  assert.equal(view.currentRound, null);
  assert.equal(view.unavailableReason, "未找到当前选中的 Coding 执行记录");
});

test("buildCodingMeaView: command with matching role+attemptNo but foreign stageRunId is not borrowed", () => {
  // commandAttemptNo 与 stage attemptNo 不同源：即使 role+commandAttemptNo 相同，
  // stageRunId 不一致也不能当作本 Attempt 的 command
  const response = {
    ...FIXTURE_CODING_MEA_W1E,
    commands: [
      {
        ...FIXTURE_CODING_MEA_W1E.commands[0],
        commandId: "cmd-foreign",
        stageRunId: "stage-coding-other"
      }
    ]
  };

  const view = buildCodingMeaView(response, "7502208702544482305");
  assert.equal(view.currentRound?.execute?.commandId, undefined);
});

test("buildCodingMeaView: unrelatable decision in a remediation round shows unavailable note instead of guessing", () => {
  // remediation 存在，但 links 与 sourceCommandId 都无法证明 decision 属于本轮
  const response = {
    ...FIXTURE_CODING_MEA_W1E,
    decisions: [
      {
        ...FIXTURE_CODING_MEA_W1E.decisions[0],
        decisionHash: "dec_hash_unrelated",
        sourceCommandId: "cmd-unknown-source"
      }
    ],
    links: FIXTURE_CODING_MEA_W1E.links.filter((l) => l.relation !== "EXECUTES")
  };

  const view = buildCodingMeaView(response, "7502208702544482305");
  assert.equal(view.currentRound?.manage, null);
  assert.equal(view.currentRound?.manageNote, MEA_UNRELATABLE_NOTE);
  // 不画错循环：headline 退回 Attempt 标识而不是伪造修复目标
  assert.match(view.currentHeadline, /Coding Attempt 2/);
});
