import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);

test("shows retrieval process and retrieved content as separate operator surfaces", () => {
  assert.match(detailPage, /检索过程/);
  assert.match(detailPage, /检索内容/);
  assert.match(detailPage, /processArtifacts/);
  assert.match(detailPage, /contentArtifacts/);
});

test("refreshes an open active retrieval run with a serialized secondary timer", () => {
  assert.match(detailPage, /scheduleActiveDetailRefresh/);
  assert.match(detailPage, /5_000/);
  assert.doesNotMatch(detailPage, /setInterval\(async/);
  assert.match(detailPage, /selectedRetrievalRun/);
});
