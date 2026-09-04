import assert from "node:assert/strict";
import test from "node:test";

import {
  canPerformGovernanceActions,
  formatSourceReference,
  governancePanelHint,
  mapGovernanceConflictMessage,
  memoryTypeLabel,
  revisionStatusBadgeClass,
  revisionStatusBadgeTone,
  revisionStatusLabel,
  sortRevisionsForDisplay,
  type ProjectMemoryRevision
} from "../src/services/projectMemoryModel.ts";

test("maps revision statuses onto the admin badge palette", () => {
  assert.equal(revisionStatusBadgeTone("ACTIVE"), "green");
  assert.equal(revisionStatusBadgeTone("CANDIDATE"), "blue");
  assert.equal(revisionStatusBadgeTone("QUARANTINED"), "orange");
  assert.equal(revisionStatusBadgeTone("SUPERSEDED"), "slate");
  assert.equal(revisionStatusBadgeTone("EXPIRED"), "slate");
  assert.equal(revisionStatusBadgeTone("REJECTED"), "red");
  assert.equal(revisionStatusBadgeTone("DELETED"), "slate");
  assert.equal(revisionStatusBadgeTone(""), "slate");
});

test("labels memory types and revision statuses for the admin table", () => {
  assert.equal(memoryTypeLabel("PROCEDURAL"), "程序记忆");
  assert.equal(memoryTypeLabel("SEMANTIC"), "语义记忆");
  assert.equal(memoryTypeLabel("EPISODIC"), "情景记忆");
  assert.equal(revisionStatusLabel("ACTIVE"), "已激活");
  assert.equal(revisionStatusLabel("CANDIDATE"), "候选");
  assert.equal(revisionStatusLabel("QUARANTINED"), "已隔离");
});

test("sorts revisions with the current head first then descending version", () => {
  const revisions: ProjectMemoryRevision[] = [
    { revisionId: "r1", version: 1, status: "SUPERSEDED", title: "", summary: "", contentHash: "", rowVersion: 1, head: false },
    { revisionId: "r3", version: 3, status: "ACTIVE", title: "", summary: "", contentHash: "", rowVersion: 3, head: true },
    { revisionId: "r2", version: 2, status: "SUPERSEDED", title: "", summary: "", contentHash: "", rowVersion: 2, head: false }
  ];
  const sorted = sortRevisionsForDisplay(revisions);
  assert.deepEqual(sorted.map((revision) => revision.revisionId), ["r3", "r2", "r1"]);
});

test("formats source references with and without live task links", () => {
  assert.match(
    formatSourceReference({
      sourceId: "s1",
      revisionId: "r1",
      projectId: "101",
      taskId: "task-9",
      stageRunId: "stage-1",
      artifactId: "artifact-1",
      sourceUri: "rd-artifact://task-9",
      sourceContentHash: "abc",
      repositoryRevision: "abc123",
      extractorVersion: "extractor-1",
      schemaVersion: "schema-1",
      redactedSummary: "redacted summary",
      originReferencesAvailable: true
    }),
    /task-9/
  );
  assert.match(
    formatSourceReference({
      sourceId: "s2",
      revisionId: "r1",
      projectId: "101",
      taskId: "",
      stageRunId: "",
      artifactId: "",
      sourceUri: "rd-artifact://missing",
      sourceContentHash: "abc",
      repositoryRevision: "abc123",
      extractorVersion: "extractor-1",
      schemaVersion: "schema-1",
      redactedSummary: "redacted summary",
      originReferencesAvailable: false
    }),
    /来源实体已不可用/
  );
});

test("maps stale row-version conflicts onto a refresh-and-retry message", () => {
  const error = {
    response: { status: 409, data: { message: "memory row version mismatch" } }
  };
  assert.equal(mapGovernanceConflictMessage(error), "版本已过期，请刷新后重试");
});

test("preserves server messages for forbidden and disabled governance", () => {
  const forbidden = {
    response: { status: 403, data: { message: "project is disabled: 102" } }
  };
  const disabled = {
    response: { status: 503, data: { message: "project memory governance disabled" } }
  };
  assert.match(mapGovernanceConflictMessage(forbidden), /disabled/);
  assert.match(mapGovernanceConflictMessage(disabled), /disabled/);
});

test("blocks governance actions when mutations are disabled or project is read-only", () => {
  assert.equal(canPerformGovernanceActions({ mutationsEnabled: true, projectReadOnly: false }), true);
  assert.equal(canPerformGovernanceActions({ mutationsEnabled: false, projectReadOnly: false }), false);
  assert.equal(canPerformGovernanceActions({ mutationsEnabled: true, projectReadOnly: true }), false);
  assert.equal(
    governancePanelHint({ mutationsEnabled: false, projectReadOnly: false }),
    "治理变更当前不可用，请联系平台管理员启用可信操作者。"
  );
  assert.equal(
    governancePanelHint({ mutationsEnabled: true, projectReadOnly: true }),
    "项目已禁用，仅可查看记忆与来源。"
  );
});

test("applies badge classes from revision status tones", () => {
  assert.match(revisionStatusBadgeClass("ACTIVE"), /green/);
  assert.match(revisionStatusBadgeClass("QUARANTINED"), /orange/);
  assert.match(revisionStatusBadgeClass("REJECTED"), /red/);
});
