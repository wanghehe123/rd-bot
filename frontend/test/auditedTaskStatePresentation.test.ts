import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import test from "node:test";

import {
  auditedCoverage,
  auditedGapIds,
  auditedRecordStatusClass,
  auditedRecordStatusLabel,
  evidenceLink,
  type AuditedRecordView
} from "../src/pages/admin/rdtask/auditedTaskStatePresentation.ts";

const records: AuditedRecordView[] = [
  {
    id: "AC-001",
    kind: "REQUIREMENT",
    blocking: true,
    text: "筛选可用",
    status: "COMPLETED",
    evidenceRefs: [
      {
        auditRunId: "audit-1",
        sourceKind: "QA_EVIDENCE",
        uri: "https://evidence.example/qa.log",
        sha256: "sha256:abc"
      }
    ],
    sourceStageRunId: "",
    blockedReason: ""
  },
  {
    id: "AC-002",
    kind: "REQUIREMENT",
    blocking: true,
    text: "回归",
    status: "PENDING",
    evidenceRefs: [],
    sourceStageRunId: "",
    blockedReason: ""
  },
  {
    id: "GATE-BUILD",
    kind: "GATE",
    blocking: true,
    text: "build",
    status: "BLOCKED",
    evidenceRefs: [],
    sourceStageRunId: "",
    blockedReason: "build failed"
  },
  {
    id: "CLAIM-1",
    kind: "FACT",
    blocking: false,
    text: "agent claim",
    status: "UNTRUSTED",
    evidenceRefs: [],
    sourceStageRunId: "stage-1",
    blockedReason: ""
  }
];

test("audited record status badges map to Chinese labels and classes", () => {
  assert.equal(auditedRecordStatusLabel("COMPLETED"), "已完成");
  assert.equal(auditedRecordStatusLabel("PENDING"), "待审计");
  assert.equal(auditedRecordStatusLabel("BLOCKED"), "已阻断");
  assert.equal(auditedRecordStatusLabel("UNTRUSTED"), "未核验");
  assert.match(auditedRecordStatusClass("COMPLETED"), /green/);
  assert.match(auditedRecordStatusClass("BLOCKED"), /rose/);
  assert.match(auditedRecordStatusClass("UNTRUSTED"), /amber/);
});

test("audited coverage label is 已审计 n/m and gaps list blocking incomplete records", () => {
  const coverage = auditedCoverage(records);
  assert.equal(coverage.completed, 1);
  assert.equal(coverage.total, 4);
  assert.equal(coverage.label, "已审计 1/4");
  assert.deepEqual(auditedGapIds(records), ["AC-002", "GATE-BUILD"]);
});

test("evidence links keep https and drop loopback or non-http URIs", () => {
  assert.deepEqual(evidenceLink("https://evidence.example/qa.log"), {
    href: "https://evidence.example/qa.log",
    label: "https://evidence.example/qa.log"
  });
  assert.equal(evidenceLink("qa-evidence://artifacts/current.log"), null);
  assert.equal(evidenceLink("http://localhost/secret"), null);
  assert.equal(evidenceLink("http://127.0.0.1/secret"), null);
});

test("rdTaskService declares audited-state APIs and path contract", async () => {
  const service = await readFile(new URL("../src/services/rdTaskService.ts", import.meta.url), "utf8");
  assert.match(service, /getRdTaskAuditedState/);
  assert.match(service, /getRdTaskAuditRuns/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/audited-state/);
  assert.match(service, /\/admin\/rd-tasks\/\$\{taskId\}\/audit-runs/);
  assert.match(service, /export interface AuditedTaskState/);
  assert.match(service, /export interface AuditRunList/);
});

test("detail page shows audited panel and 已审计 n/m summary badge", async () => {
  const [pageSource, cardSource] = await Promise.all([
    readFile(new URL("../src/pages/admin/rdtask/RdTaskDetailPage.tsx", import.meta.url), "utf8"),
    readFile(new URL("../src/components/admin/rdtask/AuditedTaskStateCard.tsx", import.meta.url), "utf8")
  ]);
  assert.match(pageSource, /getRdTaskAuditedState/);
  assert.match(pageSource, /getRdTaskAuditRuns/);
  assert.match(pageSource, /AuditedTaskStateCard/);
  assert.match(pageSource, /auditedCoverage/);
  assert.match(pageSource, /已审计/);
  assert.match(cardSource, /已审计状态/);
  assert.match(cardSource, /auditedGapIds/);
  assert.match(cardSource, /evidenceLink/);
});
