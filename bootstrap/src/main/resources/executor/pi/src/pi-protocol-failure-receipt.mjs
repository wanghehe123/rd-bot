import { createHash } from "node:crypto";
import { mkdir, rename, writeFile } from "node:fs/promises";
import { dirname } from "node:path";

import { canonicalizeStateV2 } from "./agent-state-v2-codec.mjs";

export const PI_PROTOCOL_FAILURE_RECEIPT_V1 = "PiProtocolFailureReceipt/v1";

export const PI_PROTOCOL_FAILURE_KINDS = Object.freeze({
  RESULT_MISSING_AFTER_RECOVERY: "RESULT_MISSING_AFTER_RECOVERY",
  AGENT_SETTLED_MISSING: "AGENT_SETTLED_MISSING",
  ROLE_SCHEMA_REJECTED_AFTER_RECOVERY: "ROLE_SCHEMA_REJECTED_AFTER_RECOVERY",
});

const RESULT_SOURCES = new Set(["NONE", "AGENT_RD_SUBMIT_RESULT", "BRIDGE_SYNTHETIC"]);
const REJECTION_KINDS = new Set(["NONE", "ROLE_SCHEMA"]);
const SHA256 = /^sha256:[0-9a-f]{64}$/;
const NON_EMPTY = /^[^\u0000-\u001f\u007f]+$/u;

export function canonicalizePiProtocolFailureReceipt(receipt) {
  validatePiProtocolFailureReceipt(receipt);
  return canonicalizeStateV2(receipt);
}

export function hashPiProtocolFailureReceipt(receipt) {
  return `sha256:${createHash("sha256")
    .update(canonicalizePiProtocolFailureReceipt(receipt), "utf8")
    .digest("hex")}`;
}

export async function writePiProtocolFailureReceipt(path, receipt) {
  const canonical = canonicalizePiProtocolFailureReceipt(receipt);
  await mkdir(dirname(path), { recursive: true });
  const temporary = `${path}.tmp-${process.pid}`;
  await writeFile(temporary, canonical, { encoding: "utf8", mode: 0o600 });
  await rename(temporary, path);
  return {
    canonical,
    hash: `sha256:${createHash("sha256").update(canonical, "utf8").digest("hex")}`,
  };
}

export function decodeAndVerifyPiProtocolFailureReceipt(json, expectedHash) {
  if (typeof json !== "string" || json.trim() === "") {
    throw new Error("receipt JSON must not be blank");
  }
  let receipt;
  try {
    receipt = JSON.parse(json);
  } catch (error) {
    throw new Error(`invalid receipt JSON: ${String(error.message ?? error)}`);
  }
  validatePiProtocolFailureReceipt(receipt);
  const canonical = canonicalizeStateV2(receipt);
  const actualHash = `sha256:${createHash("sha256").update(canonical, "utf8").digest("hex")}`;
  if (actualHash !== String(expectedHash ?? "").toLowerCase()) {
    throw new Error("receipt hash mismatch");
  }
  if (canonical !== json) {
    throw new Error("receipt JSON must use canonical bytes");
  }
  return receipt;
}

export function validatePiProtocolFailureReceipt(receipt) {
  if (!isPlainObject(receipt)) throw new Error("receipt must be an object");
  const expectedFields = [
    "acceptedResultDigest", "agentSettled", "attemptNo", "containerTerminated",
    "diagnosticArtifactIds", "eventStreamTrusted", "generatedAt", "kind",
    "lastRejectionDigest", "lastRejectionKind", "missingFacts", "protocol",
    "recoveryApplicable", "recoveryExhausted", "recoveryIssued", "resultSubmissionSource",
    "resultSubmitted", "role", "roleSchemaAccepted", "stageRunId", "taskId",
  ];
  const actualFields = Object.keys(receipt).sort();
  if (JSON.stringify(actualFields) !== JSON.stringify(expectedFields)) {
    throw new Error("receipt fields must exactly match v1 contract");
  }
  if (receipt.protocol !== PI_PROTOCOL_FAILURE_RECEIPT_V1) {
    throw new Error(`receipt protocol must be ${PI_PROTOCOL_FAILURE_RECEIPT_V1}`);
  }
  requireText(receipt.taskId, "taskId");
  requireText(receipt.stageRunId, "stageRunId");
  requireText(receipt.role, "role");
  if (!Number.isSafeInteger(receipt.attemptNo) || receipt.attemptNo < 1) {
    throw new Error("attemptNo must be a positive safe integer");
  }
  if (!PI_PROTOCOL_FAILURE_KINDS[receipt.kind]) throw new Error("unsupported receipt kind");
  if (!RESULT_SOURCES.has(receipt.resultSubmissionSource)) throw new Error("unsupported result submission source");
  if (!REJECTION_KINDS.has(receipt.lastRejectionKind)) throw new Error("unsupported last rejection kind");
  for (const field of [
    "resultSubmitted", "roleSchemaAccepted", "agentSettled", "eventStreamTrusted",
    "containerTerminated", "recoveryApplicable", "recoveryIssued", "recoveryExhausted",
  ]) {
    if (typeof receipt[field] !== "boolean") throw new Error(`${field} must be boolean`);
  }
  if (!Array.isArray(receipt.missingFacts) || receipt.missingFacts.some((item) => typeof item !== "string")) {
    throw new Error("missingFacts must be a string array");
  }
  if (!Array.isArray(receipt.diagnosticArtifactIds)
      || receipt.diagnosticArtifactIds.length === 0
      || receipt.diagnosticArtifactIds.some((item) => typeof item !== "string" || !NON_EMPTY.test(item))) {
    throw new Error("diagnosticArtifactIds must be a non-empty string array");
  }
  requireText(receipt.generatedAt, "generatedAt");
  if (!Number.isFinite(Date.parse(receipt.generatedAt))) throw new Error("generatedAt must be an instant");
  requireOptionalDigest(receipt.acceptedResultDigest, "acceptedResultDigest");
  requireOptionalDigest(receipt.lastRejectionDigest, "lastRejectionDigest");
  validateExactKindFacts(receipt);
  return receipt;
}

function validateExactKindFacts(receipt) {
  const facts = JSON.stringify(receipt.missingFacts);
  const recoveryAll = receipt.recoveryApplicable && receipt.recoveryIssued && receipt.recoveryExhausted;
  const noAcceptedAgentResult = receipt.resultSubmissionSource !== "AGENT_RD_SUBMIT_RESULT"
    && receipt.roleSchemaAccepted === false
    && receipt.acceptedResultDigest === "";
  if (receipt.kind === PI_PROTOCOL_FAILURE_KINDS.RESULT_MISSING_AFTER_RECOVERY) {
    if (facts !== '["AGENT_RESULT_SUBMITTED"]' || !receipt.agentSettled || !recoveryAll
        || !noAcceptedAgentResult || receipt.lastRejectionKind !== "NONE"
        || receipt.lastRejectionDigest !== "") {
      throw new Error("receipt contradicts RESULT_MISSING_AFTER_RECOVERY facts");
    }
    return;
  }
  if (receipt.kind === PI_PROTOCOL_FAILURE_KINDS.AGENT_SETTLED_MISSING) {
    if (facts !== '["AGENT_SETTLED"]' || receipt.agentSettled || !receipt.resultSubmitted
        || receipt.resultSubmissionSource !== "AGENT_RD_SUBMIT_RESULT" || !receipt.roleSchemaAccepted
        || !SHA256.test(receipt.acceptedResultDigest) || receipt.recoveryApplicable
        || receipt.recoveryIssued || receipt.recoveryExhausted || receipt.lastRejectionKind !== "NONE"
        || receipt.lastRejectionDigest !== "") {
      throw new Error("receipt contradicts AGENT_SETTLED_MISSING facts");
    }
    return;
  }
  if (facts !== '["AGENT_RESULT_SUBMITTED","ROLE_SCHEMA_ACCEPTED_RESULT"]'
      || !receipt.agentSettled || !recoveryAll || !noAcceptedAgentResult
      || receipt.lastRejectionKind !== "ROLE_SCHEMA" || !SHA256.test(receipt.lastRejectionDigest)) {
    throw new Error("receipt contradicts ROLE_SCHEMA_REJECTED_AFTER_RECOVERY facts");
  }
}

function requireText(value, field) {
  if (typeof value !== "string" || value.trim() === "" || !NON_EMPTY.test(value)) {
    throw new Error(`${field} must be non-blank text`);
  }
}

function requireOptionalDigest(value, field) {
  if (typeof value !== "string" || (value !== "" && !SHA256.test(value))) {
    throw new Error(`${field} must be blank or sha256 digest`);
  }
}

function isPlainObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
