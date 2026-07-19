import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);
const retryService = readFileSync(new URL("../src/services/taskRetryService.ts", import.meta.url), "utf8");
const reviewService = readFileSync(new URL("../src/services/aiReviewService.ts", import.meta.url), "utf8");

test("offers failed requirement retry with an explicit checkpoint confirmation", () => {
  assert.match(retryService, /retry-preview/);
  assert.match(detailPage, /从失败阶段重试/);
  assert.match(detailPage, /retryFromRole/);
  assert.match(retryService, /\/admin\/rd-tasks\/\$\{taskId\}\/retry/);
});

test("shows AI review process and review content with terminal-aware polling", () => {
  assert.match(detailPage, /AI 交付复核/);
  assert.match(detailPage, /复核过程/);
  assert.match(detailPage, /复核内容/);
  assert.match(detailPage, /立即发起复核/);
  assert.match(detailPage, /isAiReviewActive/);
  assert.match(reviewService, /\/admin\/ai-reviews\/\$\{runId\}\/artifacts/);
});
