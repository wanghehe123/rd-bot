import assert from "node:assert/strict";
import test from "node:test";
import {
  getStageResult,
  getStageResultContentUrl,
  type StageResultResponse
} from "../src/services/stageResultService.ts";
import { api } from "../src/services/api.ts";

export const FIXTURE_FINALIZED_STAGE_RESULT: StageResultResponse = {
  taskId: "7502196308401328128",
  stageRunId: "7502208702544482305",
  role: "CODING_AGENT",
  attemptNo: 2,
  artifactId: "art-final-1",
  commandId: "cmd-coding-1",
  finalizationId: "fin-001",
  source: "FINALIZATION_RESULT",
  available: true,
  unavailableReason: null,
  contentType: "application/json",
  content: "{\"status\":\"SUCCESS\",\"changedFiles\":[\"src/Payment.ts\"]}",
  truncated: false,
  contentSha256: "sha256contenthash123",
  downloadPath: "/admin/rd-tasks/7502196308401328128/stage-runs/7502208702544482305/result/content"
};

export const FIXTURE_PREVIEW_ONLY_STAGE_RESULT: StageResultResponse = {
  taskId: "7502196308401328128",
  stageRunId: "7502208702544482305",
  role: "CODING_AGENT",
  attemptNo: 1,
  artifactId: "art-preview-1",
  commandId: null,
  finalizationId: null,
  source: "ARTIFACT_PREVIEW",
  available: true,
  unavailableReason: "FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND",
  contentType: "application/json",
  content: "{\"status\":\"FAILED\",\"preview\":true}",
  truncated: true,
  contentSha256: null,
  downloadPath: null
};

test("StageResultResponse contract parses finalization and preview-only sources accurately", () => {
  assert.equal(FIXTURE_FINALIZED_STAGE_RESULT.source, "FINALIZATION_RESULT");
  assert.equal(FIXTURE_FINALIZED_STAGE_RESULT.available, true);
  assert.equal(FIXTURE_FINALIZED_STAGE_RESULT.truncated, false);
  assert.equal(typeof FIXTURE_FINALIZED_STAGE_RESULT.downloadPath, "string");

  assert.equal(FIXTURE_PREVIEW_ONLY_STAGE_RESULT.source, "ARTIFACT_PREVIEW");
  assert.equal(FIXTURE_PREVIEW_ONLY_STAGE_RESULT.truncated, true);
  assert.equal(FIXTURE_PREVIEW_ONLY_STAGE_RESULT.downloadPath, null);
  assert.equal(FIXTURE_PREVIEW_ONLY_STAGE_RESULT.unavailableReason, "FULL_RESULT_NOT_PERSISTED_OR_NOT_BOUND");
});

test("getStageResult delegates to api.get with matching stageRunId route", async () => {
  let capturedUrl = "";

  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string) => {
    capturedUrl = url;
    return Promise.resolve(FIXTURE_FINALIZED_STAGE_RESULT);
  }) as typeof originalGet;

  try {
    const result = await getStageResult("7502196308401328128", "7502208702544482305");
    assert.equal(capturedUrl, "/admin/rd-tasks/7502196308401328128/stage-runs/7502208702544482305/result");
    assert.equal(result.stageRunId, "7502208702544482305");
    assert.equal(result.source, "FINALIZATION_RESULT");
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});

test("getStageResultContentUrl generates the controlled download content path", () => {
  const url = getStageResultContentUrl("task-123", "stage-456");
  assert.equal(url, "/admin/rd-tasks/task-123/stage-runs/stage-456/result/content");
});

test("getStageResult propagates errors on 404 or failure", async () => {
  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = (() => {
    return Promise.reject(new Error("Stage result not found"));
  }) as typeof originalGet;

  try {
    await assert.rejects(
      async () => {
        await getStageResult("task-123", "nonexistent");
      },
      /Stage result not found/
    );
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});
