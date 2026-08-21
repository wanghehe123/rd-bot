import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import test from "node:test";

import {
  canonicalizePiProtocolFailureReceipt,
  decodeAndVerifyPiProtocolFailureReceipt,
  hashPiProtocolFailureReceipt,
} from "../src/pi-protocol-failure-receipt.mjs";
import { protocolFailureReceiptFromFacts } from "../src/rd-pi-bridge.mjs";

const fixturePath = resolve(
  import.meta.dirname,
  "../../../../../../../test-fixtures/protocol/pi-protocol-failure-receipt-v1-fixtures.json",
);
const fixtures = JSON.parse(await readFile(fixturePath, "utf8"));

test("matches Java shared receipt canonical and hash fixtures", () => {
  for (const fixture of fixtures.valid) {
    assert.equal(canonicalizePiProtocolFailureReceipt(fixture.input), fixture.canonical, fixture.name);
    assert.equal(hashPiProtocolFailureReceipt(fixture.input), fixture.hash, fixture.name);
    assert.deepEqual(
      decodeAndVerifyPiProtocolFailureReceipt(fixture.canonical, fixture.hash),
      fixture.input,
      fixture.name,
    );
  }
});

test("rejects shared contradictory and hash mismatch receipts", () => {
  for (const fixture of fixtures.invalid) {
    assert.throws(
      () => decodeAndVerifyPiProtocolFailureReceipt(fixture.json, fixture.hash),
      (error) => String(error.message).toLowerCase().includes(fixture.error),
      fixture.name,
    );
  }
});

test("bridge derives only the three allowlisted exact fact sets", () => {
  const request = {
    taskId: "task-1",
    stageRunId: "stage-1",
    role: "QA_AGENT",
    attemptNo: 1,
  };
  const common = {
    resultSubmitted: true,
    resultSubmissionSource: "BRIDGE_SYNTHETIC",
    roleSchemaAccepted: false,
    acceptedResultDigest: "",
    agentSettled: true,
    recoveryApplicable: true,
    recoveryIssued: true,
    recoveryExhausted: true,
    lastRejectionKind: "NONE",
    lastRejectionDigest: "",
  };

  const missing = protocolFailureReceiptFromFacts(request, common, "2026-08-18T00:00:00Z");
  assert.equal(missing.kind, "RESULT_MISSING_AFTER_RECOVERY");
  assert.deepEqual(missing.missingFacts, ["AGENT_RESULT_SUBMITTED"]);

  const schema = protocolFailureReceiptFromFacts(request, {
    ...common,
    lastRejectionKind: "ROLE_SCHEMA",
    lastRejectionDigest: "sha256:" + "b".repeat(64),
  }, "2026-08-18T00:00:00Z");
  assert.equal(schema.kind, "ROLE_SCHEMA_REJECTED_AFTER_RECOVERY");

  const unsettled = protocolFailureReceiptFromFacts(request, {
    ...common,
    resultSubmissionSource: "AGENT_RD_SUBMIT_RESULT",
    roleSchemaAccepted: true,
    acceptedResultDigest: "sha256:" + "a".repeat(64),
    agentSettled: false,
    recoveryApplicable: false,
    recoveryIssued: false,
    recoveryExhausted: false,
  }, "2026-08-18T00:00:00Z");
  assert.equal(unsettled.kind, "AGENT_SETTLED_MISSING");

  assert.equal(protocolFailureReceiptFromFacts(request, {
    ...common,
    recoveryExhausted: false,
  }), undefined);
});
