import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  buildConfirmPayload,
  buildInvalidatePayload,
  buildSoftDeletePayload
} from "../src/services/projectMemoryModel.ts";

test("project memory service targets nested admin project memory APIs under the data envelope", () => {
  const service = readFileSync(new URL("../src/services/projectMemoryService.ts", import.meta.url), "utf8");
  const model = readFileSync(new URL("../src/services/projectMemoryModel.ts", import.meta.url), "utf8");
  assert.match(service, /\/admin\/projects\/\$\{projectId\}\/memories/);
  for (const fragment of [
    "/confirm",
    "/correct",
    "/invalidate",
    "/soft-delete",
    "buildConfirmPayload",
    "buildInvalidatePayload",
    "buildSoftDeletePayload",
    "unwrapAdminData"
  ]) {
    assert.match(service, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), fragment);
  }
  for (const fragment of ["expectedMemoryRowVersion", "expectedRevisionRowVersion", "requestId"]) {
    assert.match(model, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), fragment);
  }
});

test("admin page wires project selection, status display, and governance actions", () => {
  const page = readFileSync(new URL("../src/pages/admin/project/ProjectMemoryPage.tsx", import.meta.url), "utf8");
  const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
  assert.match(app, /projects\/:projectId\/memories/);
  assert.match(page, /ProjectScopeSelector/);
  assert.match(page, /mapGovernanceConflictMessage/);
  assert.match(page, /canPerformGovernanceActions/);
  assert.match(page, /revisionStatusBadgeClass/);
  assert.match(page, /formatSourceReference/);
  assert.match(page, /confirmProjectMemory/);
  assert.match(page, /invalidateProjectMemory/);
  assert.match(page, /softDeleteProjectMemory/);
  assert.match(page, /projectReadOnly/);
  assert.match(page, /mutationsEnabled/);
});

test("governance payloads include optimistic row versions and request ids", () => {
  const confirm = buildConfirmPayload({
    revisionId: "revision-candidate",
    expectedMemoryRowVersion: 2,
    expectedRevisionRowVersion: 1,
    requestId: "req-confirm"
  });
  assert.deepEqual(confirm, {
    revisionId: "revision-candidate",
    expectedMemoryRowVersion: 2,
    expectedRevisionRowVersion: 1,
    requestId: "req-confirm"
  });

  const invalidate = buildInvalidatePayload({
    revisionId: "revision-1",
    expectedMemoryRowVersion: 2,
    expectedRevisionRowVersion: 1,
    requestId: "req-invalidate"
  });
  assert.deepEqual(invalidate, {
    revisionId: "revision-1",
    expectedMemoryRowVersion: 2,
    expectedRevisionRowVersion: 1,
    requestId: "req-invalidate"
  });

  const softDelete = buildSoftDeletePayload({
    expectedMemoryRowVersion: 2,
    requestId: "req-delete"
  });
  assert.deepEqual(softDelete, {
    expectedMemoryRowVersion: 2,
    requestId: "req-delete"
  });
});

test("project list exposes a navigation entry to project memory governance", () => {
  const projectList = readFileSync(new URL("../src/pages/admin/project/ProjectListPage.tsx", import.meta.url), "utf8");
  assert.match(projectList, /\/admin\/projects\/\$\{project\.projectId\}\/memories/);
});
