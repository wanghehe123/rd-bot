import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";

import {
  DEFAULT_INVENTORY_BACKFILL_LIMIT,
  INVENTORY_CATEGORIES,
  SUM_MISMATCH_WARNING,
  buildDuplicateResolveRequest,
  countForInventoryCategory,
  inventoryCategoryLabel,
  inventoryCategorySeverity,
  inventorySeverityClass,
  inventorySumMismatchWarning
} from "../src/services/openVikingKnowledgePresentation.ts";

const CHINESE = /[\u4e00-\u9fff]/;

const page = readFileSync(
  new URL("../src/pages/admin/knowledge/OpenVikingKnowledgePage.tsx", import.meta.url),
  "utf8"
);
const panel = readFileSync(
  new URL("../src/components/admin/knowledge/OpenVikingInventoryPanel.tsx", import.meta.url),
  "utf8"
);
const service = readFileSync(
  new URL("../src/services/openVikingKnowledgeService.ts", import.meta.url),
  "utf8"
);
const presentation = readFileSync(
  new URL("../src/services/openVikingKnowledgePresentation.ts", import.meta.url),
  "utf8"
);

test("maps all ten inventory categories onto distinct non-empty Chinese labels", () => {
  assert.deepEqual(INVENTORY_CATEGORIES, [
    "TOMBSTONE",
    "SUPERSEDED",
    "DUPLICATE_UNRESOLVED",
    "EXCLUDED_BASE_INACTIVE",
    "EXCLUDED_LOCAL_ONLY",
    "EXCLUDED_EMPTY",
    "FAILED",
    "IN_SYNC",
    "PROJECTING",
    "PENDING_BACKFILL"
  ]);

  const labels = INVENTORY_CATEGORIES.map((category) => inventoryCategoryLabel(category));
  assert.equal(labels.length, 10);
  assert.equal(new Set(labels).size, 10, "category labels must be unique");
  for (const [index, label] of labels.entries()) {
    assert.equal(typeof label, "string");
    assert.ok(label.trim().length > 0, INVENTORY_CATEGORIES[index]);
    assert.match(label, CHINESE, INVENTORY_CATEGORIES[index]);
  }

  assert.equal(inventoryCategoryLabel("EXCLUDED_LOCAL_ONLY"), "手工本地覆盖");
  assert.notEqual(inventoryCategoryLabel("EXCLUDED_LOCAL_ONLY"), inventoryCategoryLabel("PENDING_BACKFILL"));
  assert.notEqual(inventoryCategoryLabel("EXCLUDED_LOCAL_ONLY"), inventoryCategoryLabel("IN_SYNC"));
});

test("maps inventory categories onto healthy, attention, and blocked severities", () => {
  assert.equal(inventoryCategorySeverity("IN_SYNC"), "healthy");
  assert.equal(inventoryCategorySeverity("TOMBSTONE"), "healthy");
  assert.equal(inventoryCategorySeverity("SUPERSEDED"), "healthy");

  assert.equal(inventoryCategorySeverity("PENDING_BACKFILL"), "attention");
  assert.equal(inventoryCategorySeverity("PROJECTING"), "attention");
  assert.equal(inventoryCategorySeverity("EXCLUDED_LOCAL_ONLY"), "attention");
  assert.equal(inventoryCategorySeverity("EXCLUDED_EMPTY"), "attention");
  assert.equal(inventoryCategorySeverity("EXCLUDED_BASE_INACTIVE"), "attention");

  assert.equal(inventoryCategorySeverity("FAILED"), "blocked");
  assert.equal(inventoryCategorySeverity("DUPLICATE_UNRESOLVED"), "blocked");

  assert.match(inventorySeverityClass("healthy"), /green/);
  assert.match(inventorySeverityClass("attention"), /amber/);
  assert.match(inventorySeverityClass("blocked"), /red/);
});

test("reads category counts from InventoryCategory.name() keys and camelCase record fields", () => {
  const fromEnumMap = { TOMBSTONE: 1, PENDING_BACKFILL: 4, EXCLUDED_LOCAL_ONLY: 2, IN_SYNC: 9 };
  assert.equal(countForInventoryCategory(fromEnumMap, "TOMBSTONE"), 1);
  assert.equal(countForInventoryCategory(fromEnumMap, "PENDING_BACKFILL"), 4);
  assert.equal(countForInventoryCategory(fromEnumMap, "EXCLUDED_LOCAL_ONLY"), 2);
  assert.equal(countForInventoryCategory(fromEnumMap, "FAILED"), 0);

  const fromCamel = { tombstone: 3, pendingBackfill: 5, excludedLocalOnly: 7, inSync: 1 };
  assert.equal(countForInventoryCategory(fromCamel, "TOMBSTONE"), 3);
  assert.equal(countForInventoryCategory(fromCamel, "PENDING_BACKFILL"), 5);
  assert.equal(countForInventoryCategory(fromCamel, "EXCLUDED_LOCAL_ONLY"), 7);
});

test("surfaces a loud warning when category counts do not sum to the document total", () => {
  assert.ok(SUM_MISMATCH_WARNING.trim().length > 0);
  assert.match(SUM_MISMATCH_WARNING, CHINESE);
  assert.match(SUM_MISMATCH_WARNING, /不一致|不完整|未被归类|没有分类/);
  assert.equal(inventorySumMismatchWarning(false), SUM_MISMATCH_WARNING);
  assert.equal(inventorySumMismatchWarning(true), "");
});

test("builds a resolve body with expectedRowVersion for every loser", () => {
  const request = buildDuplicateResolveRequest({
    identityKey: "FEISHU:token-a",
    proposedSurvivorDocumentId: "surv-1",
    members: [
      { documentId: "surv-1", rowVersion: 4 },
      { documentId: "lose-1", rowVersion: 2 },
      { documentId: "lose-2", rowVersion: 9 }
    ]
  });
  assert.deepEqual(request, {
    identityKey: "FEISHU:token-a",
    survivorDocumentId: "surv-1",
    losers: [
      { documentId: "lose-1", expectedRowVersion: 2 },
      { documentId: "lose-2", expectedRowVersion: 9 }
    ]
  });
});

test("refuses to build a resolve body when any loser is missing expectedRowVersion", () => {
  assert.throws(
    () =>
      buildDuplicateResolveRequest({
        identityKey: "FEISHU:token-a",
        proposedSurvivorDocumentId: "surv-1",
        members: [
          { documentId: "surv-1", rowVersion: 4 },
          { documentId: "lose-1", rowVersion: 2 },
          { documentId: "lose-2" }
        ]
      }),
    /expectedRowVersion|行版本/
  );
  assert.throws(
    () =>
      buildDuplicateResolveRequest({
        identityKey: "FEISHU:token-a",
        proposedSurvivorDocumentId: "surv-1",
        members: [
          { documentId: "surv-1", rowVersion: 4 },
          { documentId: "lose-1", rowVersion: Number.NaN }
        ]
      }),
    /expectedRowVersion|行版本/
  );
});

test("inventory service talks to the six Stage C admin endpoints", () => {
  for (const fragment of [
    "/inventory",
    "/inventory/candidates",
    "/inventory/duplicates",
    "/inventory/drift",
    "/inventory/backfill",
    "/inventory/duplicates/resolve"
  ]) {
    assert.match(service, new RegExp(fragment.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")), fragment);
  }
  assert.match(service, /documentTotal/);
  assert.match(service, /sumMatchesTotal/);
  assert.match(service, /pendingBackfillRemaining/);
  assert.match(service, /inFlightOperations/);
  assert.match(service, /stopReason/);
  assert.match(service, /skippedConcurrentModification/);
  assert.match(service, /failed/);
  assert.match(service, /expectedRowVersion/);
  assert.match(service, /proposedSurvivorDocumentId/);
  assert.match(service, /KeysetPageView|OpenVikingKeysetPage/);
  assert.doesNotMatch(service, /restrictionReason/);
});

test("inventory tab renders the ledger, backfill report, confirm-to-delete, and CAS 409 copy", () => {
  assert.match(page, /存量审计/);
  assert.match(page, /OpenVikingInventoryPanel/);
  assert.match(panel, /sumMatchesTotal/);
  assert.match(panel, /SUM_MISMATCH_WARNING|inventorySumMismatchWarning/);
  assert.match(panel, /回填一批/);
  assert.match(panel, /DEFAULT_INVENTORY_BACKFILL_LIMIT|batchLimit/);
  assert.match(presentation, /DEFAULT_INVENTORY_BACKFILL_LIMIT = 20/);
  assert.equal(DEFAULT_INVENTORY_BACKFILL_LIMIT, 20);
  assert.match(panel, /stopReason/);
  assert.match(panel, /applied/);
  assert.match(panel, /skippedAlreadyBound/);
  assert.match(panel, /确认解决/);
  assert.match(panel, /远端副本将被删除/);
  assert.match(panel, /buildDuplicateResolveRequest/);
  assert.match(panel, /expectedRowVersion/);
  assert.match(panel, /mapDuplicateResolveConflictMessage/);
  assert.match(presentation, /数据已变化，请刷新后重试/);
  assert.match(panel, /AlertDialog/);
  assert.match(panel, /htmlFor|aria-label/);
  assert.match(panel, /EXCLUDED_LOCAL_ONLY|手工本地覆盖/);
  assert.match(panel, /loadInventory|getOpenVikingInventory/);
  assert.doesNotMatch(panel, /toast\.success\([^)]*\);\s*(?:setBusy|setActing|setBackfilling)/);
});
