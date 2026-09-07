import assert from "node:assert/strict";
import test from "node:test";
import {
  getCodingMea,
  getManagerDecision,
  type CodingMeaResponse,
  type DecisionReference
} from "../src/services/codingMeaService.ts";
import { api } from "../src/services/api.ts";

export const FIXTURE_CODING_MEA_W1E: CodingMeaResponse = {
  schemaVersion: 1,
  taskId: "7502196308401328128",
  codingStageRunId: "7502208702544482305",
  available: true,
  unavailableReason: null,
  snapshotReadAtEpochMillis: 1725609600000,
  taskVersion: 12,
  taskStatus: "EXECUTING",
  paused: false,
  head: {
    stateVersion: 5,
    stateHash: "abc123statehash",
    recordsTruncated: false,
    records: [
      {
        recordId: "AC-001",
        kind: "REQUIREMENT",
        title: "用户结算页面金额展示",
        status: "COMPLETED",
        blocking: true,
        evidenceIds: ["ev-01"]
      },
      {
        recordId: "AC-002",
        kind: "REQUIREMENT",
        title: "优惠券抵扣计算",
        status: "COMPLETED",
        blocking: true,
        evidenceIds: ["ev-02"]
      },
      {
        recordId: "AC-003",
        kind: "REQUIREMENT",
        title: "外卖超时赔付说明",
        status: "PENDING",
        blocking: true,
        evidenceIds: []
      }
    ]
  },
  codingStages: [
    {
      stageRunId: "7502208702544482305",
      role: "CODING_AGENT",
      attemptNo: 2,
      status: "SUCCEEDED",
      resultArtifactId: "art-coding-2",
      startedAtEpochMillis: 1725609000000,
      finishedAtEpochMillis: 1725609300000
    }
  ],
  qaStages: [
    {
      stageRunId: "7502208702544482307",
      role: "QA_AGENT",
      attemptNo: 2,
      status: "SUCCEEDED",
      resultArtifactId: "art-qa-2",
      startedAtEpochMillis: 1725609350000,
      finishedAtEpochMillis: 1725609500000
    }
  ],
  commands: [
    {
      commandId: "7502208702544482304",
      stage: "ROLE_EXECUTION:CODING_AGENT",
      role: "CODING_AGENT",
      status: "SUCCEEDED",
      stageRunId: "7502208702544482305",
      stageLinkReason: "AUDITED_STAGE_RUN",
      commandAttemptNo: 1,
      remediationRoundId: "round-gap-1",
      remediationKind: "MANAGER_GAP_FIX",
      remediationNo: 1,
      remediationSourceStageRunId: "7502196308401328129",
      createdAtEpochMillis: 1725608900000,
      updatedAtEpochMillis: 1725609310000
    }
  ],
  decisions: [
    {
      managerCommandId: "cmd-mgr-1",
      roundNo: 2,
      sourceCommandId: "7502208702544482304",
      decisionHash: "dec_hash_w1e_p157",
      route: "EXECUTE",
      executorRoute: "CODING_AGENT",
      targetRecordIds: ["AC-003"],
      boundedContractPreview: "针对未通过验收项 AC-003 进行有界修复",
      boundedContractTruncated: false,
      rationale: "QA 当前轮已通过 AC-001/002，尚余 AC-003 待修复",
      stateVersion: 4,
      stateHash: "prevstatehash123",
      stateAtDecision: {
        stateVersion: 4,
        stateHash: "prevstatehash123",
        recordsTruncated: false,
        records: [
          {
            recordId: "AC-003",
            kind: "REQUIREMENT",
            title: "外卖超时赔付说明",
            status: "PENDING",
            blocking: true,
            evidenceIds: []
          }
        ]
      },
      commandCreatedAtEpochMillis: 1725608800000
    }
  ],
  remediations: [
    {
      roundId: "round-gap-1",
      kind: "MANAGER_GAP_FIX",
      remediationNo: 1,
      sourceStageRunId: "7502196308401328129",
      targetCodingStageRunId: "7502208702544482305",
      targetQaStageRunId: "7502208702544482307",
      firstCommandId: "7502208702544482304",
      status: "COMPLETED"
    }
  ],
  hostVerifications: [
    {
      runId: "hv-001",
      codingStageRunId: "7502208702544482305",
      parentRunId: null,
      status: "SUCCEEDED",
      docsOnly: false,
      failureCategory: null
    }
  ],
  auditRuns: [],
  links: [
    {
      fromType: "COMMAND",
      fromId: "7502208702544482304",
      toType: "STAGE",
      toId: "7502208702544482305",
      relation: "EXECUTES",
      available: true,
      unavailableReason: null
    }
  ],
  page: {
    hasMore: true,
    nextCursor: "cursor_round_1"
  }
};

test("CodingMeaResponse contract preserves string IDs, nullables, and pagination", () => {
  const resp = FIXTURE_CODING_MEA_W1E;
  assert.equal(typeof resp.taskId, "string");
  assert.equal(typeof resp.codingStageRunId, "string");
  assert.equal(typeof resp.snapshotReadAtEpochMillis, "number");
  assert.equal(resp.unavailableReason, null);
  assert.equal(resp.hostVerifications[0].parentRunId, null);
  assert.equal(resp.hostVerifications[0].failureCategory, null);
  assert.equal(resp.page.hasMore, true);
  assert.equal(resp.page.nextCursor, "cursor_round_1");

  // Decision string IDs and non-empty targetRecordIds
  assert.equal(resp.decisions[0].route, "EXECUTE");
  assert.deepEqual(resp.decisions[0].targetRecordIds, ["AC-003"]);
  assert.equal(resp.decisions[0].stateAtDecision.records[0].recordId, "AC-003");
});

test("getCodingMea delegates to api.get with query parameters", async () => {
  let capturedUrl = "";
  let capturedConfig: unknown = null;

  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string, config?: unknown) => {
    capturedUrl = url;
    capturedConfig = config;
    return Promise.resolve(FIXTURE_CODING_MEA_W1E);
  }) as typeof originalGet;

  try {
    const response = await getCodingMea("7502196308401328128", {
      stageRunId: "stage-123",
      cursor: "cur-1",
      limit: 10
    });

    assert.equal(capturedUrl, "/admin/rd-tasks/7502196308401328128/coding-mea");
    assert.deepEqual((capturedConfig as { params: unknown })?.params, {
      stageRunId: "stage-123",
      cursor: "cur-1",
      limit: 10
    });
    assert.equal(response.taskId, "7502196308401328128");
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});

test("getManagerDecision delegates to api.get with exact decision hash", async () => {
  let capturedUrl = "";

  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string) => {
    capturedUrl = url;
    return Promise.resolve(FIXTURE_CODING_MEA_W1E.decisions[0]);
  }) as typeof originalGet;

  try {
    const decision: DecisionReference = await getManagerDecision("7502196308401328128", "dec_hash_w1e_p157");
    assert.equal(capturedUrl, "/admin/rd-tasks/7502196308401328128/manager-decisions/dec_hash_w1e_p157");
    assert.equal(decision.decisionHash, "dec_hash_w1e_p157");
    assert.equal(decision.route, "EXECUTE");
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});

test("getCodingMea throws on 404 or network timeout without synthesizing empty data", async () => {
  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = (() => {
    const error = new Error("Request failed with status code 404");
    return Promise.reject(error);
  }) as typeof originalGet;

  try {
    await assert.rejects(
      async () => {
        await getCodingMea("7502196308401328128");
      },
      /404/
    );
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});
