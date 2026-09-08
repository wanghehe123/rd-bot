import assert from "node:assert/strict";
import test from "node:test";
import {
  getCompleteStageResult,
  getStageResult,
  getStageResultContent,
  getStageResultContentUrl,
  shouldAutoLoadStageResult,
  stageResultSelectionSignature,
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

test("stage result auto-loading waits for a produced result and retries when availability changes", () => {
  const running = { taskId: "task-1", stageRunId: "stage-1", status: "RUNNING", resultPreview: "" };
  const succeeded = { ...running, status: "SUCCEEDED" };
  const previewed = { ...running, resultPreview: '{"summary":"ready"}' };

  assert.equal(shouldAutoLoadStageResult(running), false);
  assert.equal(shouldAutoLoadStageResult(succeeded), true);
  assert.equal(shouldAutoLoadStageResult(previewed), true);
  assert.notEqual(stageResultSelectionSignature(running), stageResultSelectionSignature(succeeded));
  assert.notEqual(stageResultSelectionSignature(running), stageResultSelectionSignature(previewed));
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

test("getCompleteStageResult follows a truncated FINALIZATION_RESULT with exact full-content identity", async () => {
  const calls: string[] = [];
  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string) => {
    calls.push(url);
    if (url.endsWith("/result")) {
      return Promise.resolve({
        ...FIXTURE_FINALIZED_STAGE_RESULT,
        taskId: "task-123",
        stageRunId: "stage-456",
        content: '{"acceptanceResults":',
        truncated: true
      });
    }
    return Promise.resolve({ acceptanceResults: [{ criteriaId: "AC-001", status: "PASSED" }] });
  }) as typeof originalGet;

  try {
    const result = await getCompleteStageResult("task-123", "stage-456");
    assert.deepEqual(calls, [
      "/admin/rd-tasks/task-123/stage-runs/stage-456/result",
      "/admin/rd-tasks/task-123/stage-runs/stage-456/result/content"
    ]);
    assert.equal(result?.stageRunId, "stage-456");
    assert.equal(result?.truncated, false);
    assert.deepEqual(JSON.parse(result?.content || ""), {
      acceptanceResults: [{ criteriaId: "AC-001", status: "PASSED" }]
    });
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});

test("getCompleteStageResult preserves stale-request checks between preview and content phases", async () => {
  let resolvePreview: ((value: StageResultResponse) => void) | undefined;
  let resolveContent: ((value: unknown) => void) | undefined;
  let current = true;
  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string) => {
    if (url.endsWith("/result")) {
      return new Promise<StageResultResponse>((resolve) => { resolvePreview = resolve; });
    }
    return new Promise<unknown>((resolve) => { resolveContent = resolve; });
  }) as typeof originalGet;

  try {
    const pending = getCompleteStageResult("task-123", "stage-456", () => current);
    resolvePreview?.({
      ...FIXTURE_FINALIZED_STAGE_RESULT,
      taskId: "task-123",
      stageRunId: "stage-456",
      content: '{"truncated":',
      truncated: true
    });
    await new Promise((resolve) => setImmediate(resolve));
    current = false;
    resolveContent?.({ acceptanceResults: [] });
    assert.equal(await pending, null);
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
});

test("getCompleteStageResult exposes full-content HTTP failures instead of returning a zero-result", async () => {
  const originalGet = api.get;
  (api as unknown as { get: typeof originalGet }).get = ((url: string) => {
    if (url.endsWith("/result")) {
      return Promise.resolve({
        ...FIXTURE_FINALIZED_STAGE_RESULT,
        taskId: "task-123",
        stageRunId: "stage-456",
        content: '{"acceptanceResults":',
        truncated: true
      });
    }
    return Promise.reject(new Error("完整结果接口 503"));
  }) as typeof originalGet;

  try {
    await assert.rejects(
      () => getCompleteStageResult("task-123", "stage-456"),
      /完整结果接口 503/
    );
  } finally {
    (api as unknown as { get: typeof originalGet }).get = originalGet;
  }
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
