import test from "node:test";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

import {
  CONTEXT_PROTOCOL_VERSION,
  deriveEnvironmentNotesFromFacts,
  validateFacts,
  validateRoleResult,
} from "../src/result-tool.mjs";

const fixtureDir = join(dirname(fileURLToPath(import.meta.url)), "fixtures");
const freshnessContext = {
  currentRevision: "abc123",
  currentWorkspaceFingerprint: "ws-1",
  harnessNow: "2026-08-01T09:00:00Z",
};

async function loadFixture(name) {
  const raw = await readFile(join(fixtureDir, name), "utf8");
  return JSON.parse(raw);
}

test("FACTS_V1 valid fixture passes shared validation", async () => {
  const result = await loadFixture("facts-v1-valid-result.json");
  const errors = validateFacts(result, CONTEXT_PROTOCOL_VERSION.FACTS_V1, freshnessContext);
  assert.deepEqual(errors, []);
  assert.deepEqual(
      deriveEnvironmentNotesFromFacts(result.facts, freshnessContext),
      result.environmentNotes,
  );
});

test("FACTS_V1 missing facts[] fails", async () => {
  const result = await loadFixture("facts-v1-missing-facts-result.json");
  const errors = validateFacts(result, CONTEXT_PROTOCOL_VERSION.FACTS_V1, freshnessContext);
  assert.ok(errors.includes("facts must be present when contextProtocolVersion is FACTS_V1"));
});

test("legacy environmentNotes-only fixture passes under LEGACY mode", async () => {
  const result = await loadFixture("legacy-environment-notes-only-result.json");
  const errors = validateFacts(result, CONTEXT_PROTOCOL_VERSION.LEGACY_ENVIRONMENT_NOTES, freshnessContext);
  assert.deepEqual(errors, []);
  const roleErrors = validateRoleResult(
      "CODING_AGENT",
      result,
      CONTEXT_PROTOCOL_VERSION.LEGACY_ENVIRONMENT_NOTES,
      freshnessContext,
  );
  assert.deepEqual(roleErrors, []);
});

test("FACTS_V1 rejects environmentNotes that do not match derived fresh OBSERVED facts", async () => {
  const result = await loadFixture("facts-v1-environment-notes-mismatch-result.json");
  const errors = validateFacts(result, CONTEXT_PROTOCOL_VERSION.FACTS_V1, freshnessContext);
  assert.ok(errors.includes("environmentNotes must match fresh OBSERVED facts derivation"));
});

test("OBSERVED facts missing source fields fail under FACTS_V1", async () => {
  const errors = validateFacts({
    facts: [{
      factId: "fact-1",
      kind: "OBSERVED",
      statement: "missing source",
      freshnessPolicy: "SAME_REVISION",
      repoRevision: "abc123",
    }],
  }, CONTEXT_PROTOCOL_VERSION.FACTS_V1, freshnessContext);
  assert.ok(errors.some((error) => error.includes("sourceArtifactId")));
  assert.ok(errors.some((error) => error.includes("sourceStageRunId")));
});
