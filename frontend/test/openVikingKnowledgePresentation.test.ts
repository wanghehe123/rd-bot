import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

const page = readFileSync(
  new URL("../src/pages/admin/knowledge/OpenVikingKnowledgePage.tsx", import.meta.url),
  "utf8"
);
const badge = readFileSync(
  new URL("../src/components/admin/knowledge/OpenVikingStatusBadge.tsx", import.meta.url),
  "utf8"
);
const timeline = readFileSync(
  new URL("../src/components/admin/knowledge/OpenVikingOperationTimeline.tsx", import.meta.url),
  "utf8"
);
const app = readFileSync(new URL("../src/App.tsx", import.meta.url), "utf8");
const documentsPage = readFileSync(
  new URL("../src/pages/admin/knowledge/KnowledgeDocumentsPage.tsx", import.meta.url),
  "utf8"
);

test("registers the OpenViking projection route and documents-page entry", () => {
  assert.match(app, /knowledge\/:kbId\/openviking/);
  assert.match(app, /OpenVikingKnowledgePage/);
  assert.match(documentsPage, /OpenViking 投影/);
  assert.match(documentsPage, /\/admin\/knowledge\/\$\{kbId\}\/openviking/);
});

test("health row, disabled banner, and summary badges drive the document table", () => {
  assert.match(page, /投影已关闭/);
  assert.match(page, /semanticConfigFingerprint/);
  assert.match(page, /unconvergedCount/);
  assert.match(page, /openFindingCount/);
  assert.match(page, /SUMMARY_PROJECTION_STATUSES/);
  assert.match(page, /setStatusFilter/);
  assert.match(page, /getOpenVikingDocuments/);
  assert.match(page, /desiredVersion/);
  assert.match(page, /observedVersion/);
  assert.match(page, /shortenChecksum/);
  assert.match(page, /remoteUri/);
  assert.match(page, /remoteTaskId/);
  assert.match(page, /lastVerifiedAtEpochMillis/);
});

test("document drawer refetches the ledger after retry, verify, and rebuild", () => {
  assert.match(page, /OpenVikingOperationTimeline/);
  assert.match(page, /retryOpenVikingDocument/);
  assert.match(page, /verifyOpenVikingDocument/);
  assert.match(page, /rebuildOpenVikingDocument/);
  assert.match(page, /getOpenVikingDocumentDetail/);
  assert.match(page, /actionOutcomeLabel/);
  assert.doesNotMatch(page, /toast\.success\([^)]*\);\s*(?:setBusy|setActing)/);
});

test("remote tree, dead letters, and tombstones are first-class tabs", () => {
  assert.match(page, /TabsTrigger/);
  assert.match(page, /远端树/);
  assert.match(page, /死信/);
  assert.match(page, /墓碑/);
  assert.match(page, /treeEntryTone/);
  assert.match(page, /FOREIGN_OWNER|foreign/);
  assert.match(page, /requeueOpenVikingDeadLetter/);
  assert.match(page, /expectedRowVersion/);
  assert.match(page, /mapRequeueConflictMessage/);
  assert.match(page, /getOpenVikingTombstones/);
});

test("polls every 5s until two consecutive stable rounds", () => {
  assert.match(page, /nextPollingState/);
  assert.match(page, /shouldKeepPolling|nextPollingState\(/);
  assert.match(page, /5_000|5000/);
});

test("status badge and operation timeline stay on presentation helpers", () => {
  assert.match(badge, /projectionBadgeClass/);
  assert.match(badge, /projectionStatusLabel/);
  assert.match(timeline, /sortOperationsNewestFirst/);
  assert.match(timeline, /displaySafeError/);
  assert.match(timeline, /operationStatusLabel/);
});
