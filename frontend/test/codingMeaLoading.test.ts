import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { createTaskRequestGuard } from "../src/pages/admin/rdtask/rdTaskDetailLoader.ts";

const detailPage = readFileSync(
  new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url),
  "utf8"
);

test("guards coding-mea async loading against stale responses and in-flight deduplication", () => {
  assert.match(detailPage, /loadCodingMea/);
  assert.match(detailPage, /codingMeaInFlightRef\.current === requestKey/);
  assert.match(detailPage, /requestSeq !== codingMeaLoadSeqRef\.current/);
  assert.match(detailPage, /!requestGuardRef\.current\.isCurrent\(requestToken\)/);
  assert.match(detailPage, /loadedCodingMeaSignatureRef\.current = `\$\{stageRunId\}:\$\{requestToken\.generation\}`/);
});

test("clears coding-mea local cache, error, and in-flight keys on task route change", () => {
  // In the taskId effect
  assert.match(detailPage, /codingMeaLoadSeqRef\.current \+= 1/);
  assert.match(detailPage, /codingMeaInFlightRef\.current = ""/);
  assert.match(detailPage, /loadedCodingMeaSignatureRef\.current = null/);
  assert.match(detailPage, /setCodingMeaResponse\(null\)/);
  assert.match(detailPage, /setCodingMeaError\(""\)/);
  assert.match(detailPage, /setLoadingCodingMea\(false\)/);
});

test("triggers coding-mea load only for coding role with a valid stageRunId", () => {
  assert.match(detailPage, /view !== "roles" \|\| \(selectedRole !== "CODING_AGENT" && selectedRole !== "BUG_CODING_AGENT"\)/);
  assert.match(detailPage, /if \(!selectedStageRunId\) return;/);
  assert.match(detailPage, /void loadCodingMea\(selectedStageRunId\)/);
});

test("separates prompt loading from heavy evidence loading", () => {
  // role-prompts triggers on view === "roles"
  assert.match(detailPage, /if \(view !== "roles"\) return;\s*if \(!executionOverview \|\| rolePromptSignature === loadedRolePromptSignatureRef\.current\) return;\s*void loadRolePrompts\(rolePromptSignature\);/);
  // heavy evidence triggers only on selectedRoleTab === "evidence"
  assert.match(detailPage, /if \(selectedRoleTab === "evidence"\) \{\s*if \(rolePromptSignature !== loadedRoleEvidenceSignatureRef\.current\) \{\s*void loadRoleEvidenceData\(rolePromptSignature\);/);
});

test("request guard discards late response from task A after navigation to task B", async () => {
  const guard = createTaskRequestGuard();

  // Begin task A
  guard.beginTask("task-A");
  const tokenA = guard.capture("task-A");
  assert.equal(guard.isCurrent(tokenA), true);

  // User navigates to task B
  guard.beginTask("task-B");
  const tokenB = guard.capture("task-B");

  // Task A response arrives late
  assert.equal(guard.isCurrent(tokenA), false, "Late response from task A must be discarded");
  assert.equal(guard.isCurrent(tokenB), true, "Current response for task B must be accepted");
});
