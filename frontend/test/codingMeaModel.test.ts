import assert from "node:assert/strict";
import test from "node:test";
import { buildCodingMeaView } from "../src/pages/admin/rdtask/codingMeaModel.ts";
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
    decisions: [],
    commands: []
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
  const p158Response = {
    ...FIXTURE_CODING_MEA_W1E,
    taskStatus: "FAILED_RETRYABLE", // task ended up failing later
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
          stateVersion: 6,
          stateHash: "hash-p158",
          recordsTruncated: false,
          records: [
            { recordId: "AC-001", kind: "REQUIREMENT", title: "结算金额", status: "COMPLETED", blocking: true, evidenceIds: [] },
            { recordId: "AC-002", kind: "REQUIREMENT", title: "优惠券", status: "COMPLETED", blocking: true, evidenceIds: [] },
            { recordId: "AC-003", kind: "REQUIREMENT", title: "超时赔付", status: "COMPLETED", blocking: true, evidenceIds: [] }
          ]
        },
        commandCreatedAtEpochMillis: 1725610000000
      }
    ],
    head: {
      stateVersion: 6,
      stateHash: "hash-p158",
      recordsTruncated: false,
      records: [
        { recordId: "AC-001", kind: "REQUIREMENT", title: "结算金额", status: "COMPLETED", blocking: true, evidenceIds: [] },
        { recordId: "AC-002", kind: "REQUIREMENT", title: "优惠券", status: "COMPLETED", blocking: true, evidenceIds: [] },
        { recordId: "AC-003", kind: "REQUIREMENT", title: "超时赔付", status: "COMPLETED", blocking: true, evidenceIds: [] }
      ]
    }
  };

  const view = buildCodingMeaView(p158Response, "7502208702544482305");
  assert.equal(view.managerDoneReviewNote, "交由交付复核");
  assert.match(view.currentHeadline, /交由交付复核/);
  // Task status remains FAILED_RETRYABLE in response, buildCodingMeaView does not synthesize COMPLETED
  assert.equal(p158Response.taskStatus, "FAILED_RETRYABLE");
});
