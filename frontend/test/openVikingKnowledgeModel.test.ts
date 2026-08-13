import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  displaySafeError,
  isProjectionDisabledConflict,
  isTerminalOperationStatus,
  isTransientProjectionStatus,
  mapRequeueConflictMessage,
  nextPollingState,
  projectionBadgeTone,
  shouldKeepPolling,
  treeEntryTone,
  unwrapAdminData
} from "../src/services/openVikingKnowledgePresentation.ts";

test("maps projection statuses onto the admin badge palette", () => {
  assert.equal(projectionBadgeTone("IN_SYNC"), "green");
  assert.equal(projectionBadgeTone("PENDING"), "blue");
  assert.equal(projectionBadgeTone("PROCESSING"), "blue");
  assert.equal(projectionBadgeTone("DRIFTED"), "amber");
  assert.equal(projectionBadgeTone("FAILED"), "red");
  assert.equal(projectionBadgeTone("DEAD_LETTER"), "red");
  assert.equal(projectionBadgeTone("NEEDS_HUMAN"), "orange");
  assert.equal(projectionBadgeTone("DELETING"), "slate");
  assert.equal(projectionBadgeTone("DELETED"), "slate");
  assert.equal(projectionBadgeTone(""), "slate");
  assert.equal(projectionBadgeTone("UNEXPECTED"), "slate");
});

test("treats SUCCEEDED, SUPERSEDED, and DEAD_LETTER as terminal operations", () => {
  assert.equal(isTerminalOperationStatus("SUCCEEDED"), true);
  assert.equal(isTerminalOperationStatus("SUPERSEDED"), true);
  assert.equal(isTerminalOperationStatus("DEAD_LETTER"), true);
  for (const status of [
    "PENDING",
    "CLAIMED",
    "UPLOADING",
    "SUBMITTED",
    "WAITING_REMOTE",
    "VERIFYING",
    "UNKNOWN_REMOTE_RESULT",
    "RETRY_WAIT",
    "NEEDS_HUMAN"
  ]) {
    assert.equal(isTerminalOperationStatus(status), false, status);
  }
});

test("keeps polling while a non-terminal operation is in flight", () => {
  assert.equal(shouldKeepPolling({ operations: [{ status: "VERIFYING" }] }), true);
  assert.equal(shouldKeepPolling({ operations: [{ status: "UNKNOWN_REMOTE_RESULT" }] }), true);
  assert.equal(shouldKeepPolling({ operations: [{ status: "SUCCEEDED" }] }), false);
  assert.equal(shouldKeepPolling({ operations: [{ status: "SUPERSEDED" }] }), false);
  assert.equal(shouldKeepPolling({ operations: [{ status: "DEAD_LETTER" }] }), false);
});

test("keeps polling while documents are PENDING, PROCESSING, or DELETING", () => {
  assert.equal(isTransientProjectionStatus("PENDING"), true);
  assert.equal(isTransientProjectionStatus("PROCESSING"), true);
  assert.equal(isTransientProjectionStatus("DELETING"), true);
  assert.equal(isTransientProjectionStatus("IN_SYNC"), false);
  assert.equal(shouldKeepPolling({ documents: [{ projectionStatus: "PROCESSING" }] }), true);
  assert.equal(shouldKeepPolling({ documents: [{ projectionStatus: "PENDING" }] }), true);
  assert.equal(shouldKeepPolling({ documents: [{ projectionStatus: "DELETING" }] }), true);
  assert.equal(shouldKeepPolling({ documents: [{ projectionStatus: "IN_SYNC" }] }), false);
  assert.equal(shouldKeepPolling({ bindingCounts: { PROCESSING: 2, IN_SYNC: 4 } }), true);
  assert.equal(shouldKeepPolling({ outboxCounts: { WAITING_REMOTE: 1, SUCCEEDED: 9 } }), true);
  assert.equal(shouldKeepPolling({ outboxCounts: { SUCCEEDED: 9, DEAD_LETTER: 1 } }), false);
  assert.equal(shouldKeepPolling({ unconvergedCount: 3 }), true);
  assert.equal(shouldKeepPolling({ unconvergedCount: 0, documents: [{ projectionStatus: "IN_SYNC" }] }), false);
});

test("stops polling after two consecutive stable rounds", () => {
  const stable = { documents: [{ projectionStatus: "IN_SYNC" }], operations: [{ status: "SUCCEEDED" }] };
  const first = nextPollingState(0, stable);
  assert.equal(first.keepPolling, true);
  assert.equal(first.consecutiveStableRounds, 1);

  const second = nextPollingState(1, stable);
  assert.equal(second.keepPolling, false);
  assert.equal(second.consecutiveStableRounds, 2);

  const afterActivity = nextPollingState(1, { operations: [{ status: "UPLOADING" }] });
  assert.equal(afterActivity.keepPolling, true);
  assert.equal(afterActivity.consecutiveStableRounds, 0);
});

test("maps a stale requeue 409 onto a refresh-and-retry message", () => {
  const error = {
    response: { status: 409, data: { message: "row_version 已过期，请刷新后重试" } }
  };
  assert.equal(mapRequeueConflictMessage(error), "版本已过期，请刷新后重试");
});

test("does not treat a disabled-projection 409 as a stale row version", () => {
  const error = {
    response: { status: 409, data: { message: "投影已关闭，无法核验远端" } }
  };
  assert.equal(isProjectionDisabledConflict(error), true);
  assert.match(mapRequeueConflictMessage(error), /投影已关闭/);
  assert.equal(
    isProjectionDisabledConflict({
      response: { status: 409, data: { message: "row_version 已过期，请刷新后重试" } }
    }),
    false
  );
});

test("falls back when error code and message are blank", () => {
  assert.equal(displaySafeError("", ""), "无错误详情");
  assert.equal(displaySafeError(null, "   "), "无错误详情");
  assert.equal(displaySafeError(undefined, undefined), "无错误详情");
  assert.equal(displaySafeError("PATH_BUSY", ""), "PATH_BUSY");
  assert.equal(displaySafeError("", "remote busy"), "remote busy");
  assert.equal(displaySafeError("PATH_BUSY", "remote busy"), "remote busy");
});

test("unwraps the admin success envelope and rejects a missing data field", () => {
  assert.deepEqual(unwrapAdminData({ data: { ready: true } }), { ready: true });
  assert.equal(unwrapAdminData({ data: 0 }), 0);
  assert.throws(() => unwrapAdminData({}), /data/);
  assert.throws(() => unwrapAdminData(null), /data/);
  assert.throws(() => unwrapAdminData("ready"), /data/);
});

test("marks foreign and orphan remote-tree rows for warning styling", () => {
  assert.equal(treeEntryTone({ owner: "rd-bot", uri: "viking://owned" }, { knownRemoteUris: ["viking://owned"] }), "owned");
  assert.equal(treeEntryTone({ owner: "rd.owner=rd-bot", uri: "viking://owned" }, {}), "owned");
  assert.equal(treeEntryTone({ owner: "other-team", uri: "viking://foreign" }, {}), "foreign");
  assert.equal(
    treeEntryTone({ owner: "rd-bot", uri: "viking://orphan" }, { findingTypes: ["ORPHAN_REMOTE"] }),
    "orphan"
  );
  assert.equal(
    treeEntryTone(
      { owner: "rd-bot", uri: "viking://orphan" },
      { knownRemoteUris: ["viking://owned"] }
    ),
    "orphan"
  );
});

test("OpenViking service talks to nested knowledge-base APIs under the data envelope", () => {
  const source = readFileSync(
    new URL("../src/services/openVikingKnowledgeService.ts", import.meta.url),
    "utf8"
  );
  assert.match(source, /\/admin\/knowledge-base\/\$\{kbId\}\/openviking/);
  for (const fragment of [
    "/overview",
    "/documents",
    "/tree",
    "/health",
    "/dead-letters",
    "/tombstones",
    "/retry",
    "/verify",
    "/rebuild",
    "/requeue",
    "/reconcile",
    "expectedRowVersion",
    "unwrapAdminData"
  ]) {
    assert.match(source, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), fragment);
  }
  assert.doesNotMatch(source, /\/admin\/knowledge\/\$\{kbId\}\/openviking/);
});
