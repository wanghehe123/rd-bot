import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

import { EventNormalizer } from "../src/event-normalizer.mjs";
import {
  EVENT_TYPES,
  INPUT_MANIFEST_PATH,
  REQUEST_PROTOCOL,
  REQUEST_PROTOCOL_V2,
  SKILL_MANIFEST_PATH,
  normalizePiEvent,
  parseJsonLine,
  redact,
  validateContextPolicy,
  validateRequest,
  validateRequestV2,
  validatedInitialAgentStateV2,
} from "../src/protocol.mjs";

test("normalized protocol exposes v2 snapshot and injection events", () => {
  assert.ok(EVENT_TYPES.includes("STATE_SNAPSHOT_UPDATED"));
  assert.ok(EVENT_TYPES.includes("STATE_CONTEXT_INJECTED"));
});
import { canonicalizeStateV2, hashStateV2 } from "../src/agent-state-v2-codec.mjs";
import { validateResult, validateRoleResult, loadFrozenAcceptanceCriteriaIds } from "../src/result-tool.mjs";
import * as resultTool from "../src/result-tool.mjs";
import { classifyDocsOnlyChange, DOCS_ONLY_DECISION } from "../src/docs-only.mjs";
import {
  hashResourcePath,
  validateResourceManifest,
} from "../src/resource-loader.mjs";
import { EventSink, createObservabilityExtension, executionPrompt, writeRuntimeContextManifest } from "../src/rd-pi-bridge.mjs";
import * as bridge from "../src/rd-pi-bridge.mjs";
import { mkdtempSync, mkdirSync, writeFileSync, rmSync } from "node:fs";
import { mkdtemp, mkdir, readFile, stat, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";

const request = {
  protocol: "rd-pi-request/v1",
  snapshotId: "snapshot-1",
  stageRunId: "stage-1",
  taskId: "task-1",
  role: "CODING_AGENT",
  prompt: "implement the change",
  provider: "anthropic",
  model: "claude-sonnet-4-5",
  repoPath: "/work/repo",
  inputPath: "/work/input",
  outputPath: "/work/output",
  resourceManifestPath: "/work/input/resource-manifest.json",
  skillManifestPath: SKILL_MANIFEST_PATH,
  credentialEnvironmentVariable: "ANTHROPIC_API_KEY",
  toolPolicy: { allow: ["read", "bash", "edit", "write", "rd_submit_result"] },
};

const requestV2 = {
  ...request,
  protocol: REQUEST_PROTOCOL_V2,
  inputManifestPath: INPUT_MANIFEST_PATH,
  inputManifestHash: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  contextPolicy: {
    protocol: "rd-runtime-context-policy/v1",
    mode: "ROOT_ONLY",
    policyHash: "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    expectedFiles: [{ path: "AGENTS.md", contentHash: "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc" }],
  },
};

const initialState = {
  protocol: "rd-agent-state/v2",
  sequence: 0,
  taskId: "task-1",
  stageRunId: "stage-1",
  role: "CODING_AGENT",
  attemptNo: 1,
  runtimeType: "PI",
  profileSnapshotId: "snapshot-1",
  currentGoal: "implement and verify",
  budget: { availability: "UNKNOWN" },
  todos: [],
};
const initialStateJson = canonicalizeStateV2(initialState);
const stateV2Request = {
  ...requestV2,
  dynamicStateEnabled: true,
  agentStateSchemaVersion: "rd-agent-state/v2",
  attemptNo: 1,
  initialAgentStateProtocol: "rd-agent-state/v2",
  initialAgentStateJson: initialStateJson,
  initialAgentStateHash: hashStateV2(initialState),
};

test("keeps v1 as the default request protocol constant", () => {
  assert.equal(REQUEST_PROTOCOL, "rd-pi-request/v1");
  assert.equal(REQUEST_PROTOCOL_V2, "rd-pi-request/v2");
});

test("validates the fixed one-shot request contract", () => {
  assert.equal(validateRequest(request), request);
  assert.throws(() => validateRequest({ ...request, credentialEnvironmentVariable: "secret-value" }));
  assert.throws(() => validateRequest({ ...request, repoPath: "/tmp/repo" }));
  assert.throws(() => validateRequest({
    ...request,
    skillManifestPath: "/work/input/other-skill-manifest.json",
  }));
  const withoutSkillManifest = { ...request };
  delete withoutSkillManifest.skillManifestPath;
  assert.equal(validateRequest(withoutSkillManifest), withoutSkillManifest);
});

test("validates request v2 fixed manifest path, hashes, and context policy", () => {
  assert.equal(validateRequestV2(requestV2), requestV2);
  assert.throws(() => validateRequestV2({ ...requestV2, protocol: REQUEST_PROTOCOL }));
  assert.throws(() => validateRequestV2({ ...requestV2, inputManifestPath: "/work/input/other.json" }));
  assert.throws(() => validateRequestV2({ ...requestV2, inputManifestHash: "deadbeef" }));
  assert.throws(() => validateRequestV2({
    ...requestV2,
    contextPolicy: { ...requestV2.contextPolicy, mode: "IMPLICIT_NESTED" },
  }));
  assert.throws(() => validateRequestV2({
    ...requestV2,
    contextPolicy: { ...requestV2.contextPolicy, policyHash: "not-a-hash" },
  }));
});

test("binds Host initial v2 state on request v1 when kill switch and initial bytes are present", () => {
  const v1WithState = {
    ...request,
    dynamicStateEnabled: true,
    agentStateSchemaVersion: "rd-agent-state/v2",
    attemptNo: 1,
    initialAgentStateProtocol: "rd-agent-state/v2",
    initialAgentStateJson: initialStateJson,
    initialAgentStateHash: hashStateV2(initialState),
  };
  assert.equal(validateRequest(v1WithState), v1WithState);
  assert.deepEqual(validatedInitialAgentStateV2(v1WithState), initialState);
});

test("binds Host initial v2 state even if snapshot schema version stayed on v1", () => {
  const staleSchema = {
    ...request,
    dynamicStateEnabled: true,
    agentStateSchemaVersion: "rd-agent-state/v1",
    attemptNo: 1,
    initialAgentStateProtocol: "rd-agent-state/v2",
    initialAgentStateJson: initialStateJson,
    initialAgentStateHash: hashStateV2(initialState),
  };
  assert.deepEqual(validatedInitialAgentStateV2(staleSchema), initialState);
});

test("requires and verifies Host initial state for enabled state v2 requests", () => {
  assert.equal(validateRequestV2(stateV2Request), stateV2Request);
  assert.deepEqual(validatedInitialAgentStateV2(stateV2Request), initialState);
  const missing = { ...stateV2Request };
  delete missing.initialAgentStateJson;
  assert.throws(() => validateRequestV2(missing), /initial agent state.*required/i);
  assert.throws(() => validateRequestV2({
    ...stateV2Request,
    initialAgentStateHash: "sha256:0000000000000000000000000000000000000000000000000000000000000000",
  }), /hash mismatch/i);
  const mismatched = {
    ...initialState,
    stageRunId: "stage-other",
  };
  assert.throws(() => validateRequestV2({
    ...stateV2Request,
    initialAgentStateJson: canonicalizeStateV2(mismatched),
    initialAgentStateHash: hashStateV2(mismatched),
  }), /identity/i);
});

test("validates runtime context policy objects independently", () => {
  assert.equal(validateContextPolicy(requestV2.contextPolicy), requestV2.contextPolicy);
  assert.throws(() => validateContextPolicy({
    ...requestV2.contextPolicy,
    protocol: "rd-runtime-context-policy/v0",
  }));
  assert.throws(() => validateContextPolicy({
    ...requestV2.contextPolicy,
    expectedFiles: [{ path: "AGENTS.md", contentHash: "bad" }],
  }));
});

test("accepts optional coding-benchmark patch and budget fields", () => {
  const withFields = {
    ...request,
    patchArtifactPath: "/work/output/candidate.patch",
    maxAgentTurns: 40,
    maxTotalTokens: 2_000_000,
  };
  assert.equal(validateRequest(withFields), withFields);
  assert.throws(() => validateRequest({ ...request, patchArtifactPath: "/tmp/x" }));
  assert.throws(() => validateRequest({ ...request, maxAgentTurns: 0 }));
});

test("uses patchArtifactPath in coding prompt when provided", () => {
  const coding = executionPrompt({
    ...request,
    role: "CODING_AGENT",
    patchArtifactPath: "/work/output/candidate.patch",
  });
  assert.match(coding, /candidate\.patch/);
});

test("uses an opaque lease as the Pi runtime credential without redeeming a provider secret", () => {
  assert.equal(typeof bridge.redeemPiCredentialLease, "undefined");
  assert.equal(typeof bridge.resolvePiProviderCredential, "function");

  const credential = bridge.resolvePiProviderCredential(request, {
    RD_PI_CREDENTIAL_RELAY_ENABLED: "true",
    RD_PI_CREDENTIAL_LEASE: "pcl_test",
    ANTHROPIC_API_KEY: "super-secret",
  });

  assert.equal(credential, "pcl_test");
  assert.notEqual(credential, "super-secret");
  assert.doesNotMatch(JSON.stringify({ credential }), /super-secret/);
});

test("fails closed when relay mode has no opaque lease", () => {
  assert.throws(
    () => bridge.resolvePiProviderCredential(request, {
      RD_PI_CREDENTIAL_RELAY_ENABLED: "true",
      ANTHROPIC_API_KEY: "super-secret",
    }),
    /opaque lease/i,
  );
});

test("tailors the execution prompt to each delivery role", () => {
  const coding = executionPrompt({ ...request, role: "CODING_AGENT" });
  assert.match(coding, /patch\.diff/);
  assert.match(coding, /test\.log/);
  assert.match(coding, /never delete node_modules or package-lock\.json/i);
  assert.match(coding, /npm run build && npm run start/);
  assert.match(coding, /HTTP.*request timeout/i);
  assert.doesNotMatch(coding, /handoff\/next\.md/);

  const reviewer = executionPrompt({ ...request, role: "REQUIREMENT_REVIEWER" });
  assert.match(reviewer, /handoff\/next\.md/);
  assert.match(reviewer, /REQUIREMENT_REVIEWER role protocol/);
  assert.doesNotMatch(reviewer, /patch\.diff/);

  const architect = executionPrompt({ ...request, role: "SOLUTION_ARCHITECT" });
  assert.match(architect, /handoff\/next\.md/);
  assert.match(architect, /SOLUTION_ARCHITECT role protocol/);

  const qa = executionPrompt({ ...request, role: "QA_AGENT" });
  assert.match(qa, /QA_AGENT role protocol/);
  assert.match(qa, /do not fabricate/i);
  assert.doesNotMatch(qa, /patch\.diff/);
});

test("enforces the bridge-owned hard deadline for every bash tool call", async () => {
  const handlers = new Map();
  const extension = createObservabilityExtension(
    { lifecycle: async () => {} },
    { stageRunId: "stage-1", taskId: "task-1" },
    5_000,
  );
  extension.factory({ on(eventName, handler) { handlers.set(eventName, handler); } });
  const toolCall = handlers.get("tool_call");

  const omittedTimeout = { toolName: "bash", input: { command: "node smoke.mjs" } };
  await toolCall(omittedTimeout);
  assert.equal(omittedTimeout.input.timeout, 5_000);

  const smallerTimeout = { toolName: "bash", input: { command: "npm test", timeout: 2_000 } };
  await toolCall(smallerTimeout);
  assert.equal(smallerTimeout.input.timeout, 2_000);

  const oversizedTimeout = { toolName: "bash", input: { command: "npm run build", timeout: 60_000 } };
  await toolCall(oversizedTimeout);
  assert.equal(oversizedTimeout.input.timeout, 5_000);
});

test("rejects oversized or malformed JSON lines", () => {
  assert.deepEqual(parseJsonLine("{\"ok\":true}"), { ok: true });
  assert.throws(() => parseJsonLine("{"));
  assert.throws(() => parseJsonLine("x".repeat(20), 8));
});

test("caps the private Pi raw-event log without dropping normalized runtime events", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-raw-limit-"));
  const paths = {
    events: join(root, "agent-events.jsonl"),
    rawEvents: join(root, "private", "pi-raw-events.jsonl"),
  };
  await mkdir(join(root, "private"), { recursive: true });
  const sink = new EventSink(paths, {
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "CODING_AGENT",
    runtimeType: "PI",
    snapshotId: "snapshot-1",
    provider: "anthropic",
    model: "claude-sonnet-4-5",
  }, 128);

  await sink.ingest({
    type: "message_update",
    assistantMessageEvent: { type: "text_delta", delta: "first" },
  });
  await sink.ingest({ type: "unknown-event", output: "x".repeat(512) });
  await sink.flush();

  assert.ok((await stat(paths.rawEvents)).size <= 128);
  assert.match(await readFile(paths.rawEvents, "utf8"), /first/);
  assert.match(await readFile(paths.events, "utf8"), /ASSISTANT_TEXT_DELTA/);
  assert.equal(sink.rawEventStats().truncated, true);
});

test("normalizes text and tool events without exposing thinking or secrets", () => {
  const normalizer = new EventNormalizer();
  const context = {
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "CODING_AGENT",
    runtimeType: "PI",
    snapshotId: "snapshot-1",
    provider: "anthropic",
    model: "claude-sonnet-4-5",
  };
  assert.equal(normalizer.next({ type: "message_update", assistantMessageEvent: { type: "thinking_delta", delta: "private" } }, context), null);
  const textEvent = normalizer.next({ type: "message_update", assistantMessageEvent: { type: "text_delta", delta: "hello" } }, context);
  assert.equal(textEvent.eventType, "ASSISTANT_TEXT_DELTA");
  assert.equal(textEvent.runtimeType, "PI");
  assert.equal(textEvent.snapshotId, "snapshot-1");
  assert.equal(textEvent.provider, "anthropic");
  assert.equal(textEvent.model, "claude-sonnet-4-5");
  const settled = normalizer.next({ type: "agent_settled" }, context);
  assert.equal(settled.eventType, "AGENT_SETTLED");
  const noSettledOnRetry = normalizer.next({ type: "agent_end", willRetry: true }, context);
  assert.equal(noSettledOnRetry, null);
  assert.deepEqual(redact({ apiKey: "secret", nested: { token: "hidden" } }), {
    apiKey: "[REDACTED]",
    nested: { token: "[REDACTED]" },
  });
});

test("keeps v2 budget token counters when redacting STATE_SNAPSHOT_UPDATED", () => {
  const snapshot = {
    protocol: "rd-agent-state/v2",
    sequence: 0,
    taskId: "task-1",
    stageRunId: "stage-1",
    role: "REQUIREMENT_REVIEWER",
    attemptNo: 1,
    runtimeType: "PI",
    profileSnapshotId: "snapshot-1",
    currentGoal: "审查并澄清需求",
    generatedAtEpochMillis: 1787157846103,
    phase: "REQUIREMENT_REVIEWER_EXECUTION",
    taskStartedAtEpochMillis: 1787157845320,
    stageStartedAtEpochMillis: 1787157846103,
    budget: {
      availability: "UNKNOWN",
      estimatedInputTokens: 1613,
      maxContextTokens: 128000,
      reservedOutputTokens: 4096,
      estimatorVersion: "chars/4-v1",
      model: "LongCat-2.0",
    },
    todos: [],
  };
  const stateHash = hashStateV2(snapshot);
  const redacted = redact({
    stateSequence: 0,
    stateHash,
    snapshot,
  });
  assert.equal(redacted.snapshot.budget.estimatedInputTokens, 1613);
  assert.equal(redacted.snapshot.budget.maxContextTokens, 128000);
  assert.equal(redacted.snapshot.budget.reservedOutputTokens, 4096);
  assert.equal(hashStateV2(redacted.snapshot), stateHash);
  const normalized = new EventNormalizer().lifecycle("STATE_SNAPSHOT_UPDATED", {
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "REQUIREMENT_REVIEWER",
    runtimeType: "PI",
    snapshotId: "snapshot-1",
  }, {
    stateSequence: 0,
    stateHash,
    snapshot,
  });
  assert.equal(hashStateV2(normalized.payload.snapshot), stateHash);
  assert.equal(normalized.payload.snapshot.budget.estimatedInputTokens, 1613);
});

test("keeps a tool call correlated while exposing only a redacted invocation summary", () => {
  const context = {
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "QA_AGENT",
    runtimeType: "PI",
    snapshotId: "snapshot-1",
    provider: "anthropic",
    model: "claude-sonnet-4-5",
  };
  const rawCommand = "TOKEN=super-secret curl -H 'Authorization: Bearer private-token' https://example.test?api_key=top-secret";
  const started = normalizePiEvent({
    type: "tool_execution_start",
    toolCallId: "call-42",
    toolName: "bash",
    args: { command: rawCommand },
  }, context, 1);
  const progressed = normalizePiEvent({
    type: "tool_execution_update",
    toolCallId: "call-42",
    toolName: "bash",
    partialResult: { content: [{ type: "text", text: "Authorization: Bearer private-token" }] },
  }, context, 2);
  const completed = normalizePiEvent({
    type: "tool_execution_end",
    toolCallId: "call-42",
    toolName: "bash",
    isError: false,
  }, context, 3);

  assert.deepEqual(started.payload, {
    toolName: "bash",
    toolCallId: "call-42",
    displaySummary: "TOKEN=[REDACTED] curl -H 'Authorization: Bearer [REDACTED]' https://example.test?api_key=[REDACTED]",
  });
  assert.deepEqual(progressed.payload, {
    toolName: "bash",
    toolCallId: "call-42",
  });
  assert.deepEqual(completed.payload, {
    toolName: "bash",
    toolCallId: "call-42",
    isError: false,
  });
  assert.doesNotMatch(JSON.stringify([started, progressed, completed]), /super-secret|private-token|top-secret/);
});

test("requires a versioned structured result", () => {
  assert.deepEqual(validateResult({ status: "SUCCESS", summary: "done" }), {
    status: "SUCCESS",
    summary: "done",
  });
  assert.throws(() => validateResult({ status: "OK", summary: "done" }));
});

test("rejects an incomplete QA submission in-session with the full violation list", () => {
  const errors = validateRoleResult("QA_AGENT", { status: "FAILED", summary: "browser blocked" });
  assert.ok(errors.some((error) => error.includes("failureCategory")));
  assert.ok(errors.some((error) => error.includes("retryRecommendation")));
  assert.ok(errors.some((error) => error.includes("acceptanceResults")));
  assert.ok(errors.some((error) => error.includes("browserValidation")));
});

test("accepts a complete QA report and enforces cross-field consistency", () => {
  const report = {
    status: "FAILED",
    summary: "regression broke checkout",
    failureCategory: "PRODUCT_DEFECT",
    retryRecommendation: "CODING_AGENT",
    evidenceManifestArtifactId: "qa-evidence/manifest.json",
    browserValidation: {
      required: true,
      performed: true,
      decisionSource: "AUTO_DETECTION",
      baseUrl: "http://127.0.0.1:3000",
      browser: "chromium",
      viewports: ["desktop-1440x900", "mobile-390x844"],
    },
    acceptanceResults: [
      {
        criteria: "search works",
        scope: "CURRENT",
        command: "npm test",
        status: "FAILED",
        exitCode: 1,
        durationMillis: 1200,
        logArtifactId: "qa-evidence/commands/test.log",
        evidenceArtifactIds: [
          "qa-evidence/screenshots/current-desktop.png",
          "qa-evidence/screenshots/current-mobile.png",
          "qa-evidence/console/current.log",
          "qa-evidence/network/current.log",
          "qa-evidence/traces/current.zip",
        ],
      },
      {
        criteria: "existing pages render",
        scope: "REGRESSION",
        command: "npm run build",
        status: "PASSED",
        exitCode: 0,
        durationMillis: 4000,
        logArtifactId: "qa-evidence/commands/build.log",
        evidenceArtifactIds: ["qa-evidence/commands/build.log"],
      },
    ],
  };
  assert.deepEqual(validateRoleResult("QA_AGENT", report), []);
  const inconsistent = { ...report, status: "PASSED" };
  assert.ok(validateRoleResult("QA_AGENT", inconsistent)
      .some((error) => error.includes("requires all acceptanceResults")));
});

test("accepts an explicit PI-v2 QA remediation request independent of failureCategory", () => {
  const report = completeQaReport("qa-evidence/commands/current.log");
  report.status = "FAILED";
  report.summary = "runtime failure reveals a product bug";
  report.failureCategory = "ENVIRONMENT";
  report.retryRecommendation = "CODING_AGENT";
  report.acceptanceResults[0] = {
    ...report.acceptanceResults[0],
    criteriaId: "ac-current-1",
    status: "FAILED",
    exitCode: 1,
  };
  report.acceptanceResults[1].criteriaId = "ac-regression-1";
  report.remediationRequest = {
    requested: true,
    targetRole: "CODING_AGENT",
    reason: "The coding agent must fix the reproducible checkout bug.",
    bugFindingIds: ["bug-1"],
  };
  report.bugFindings = [{
    id: "bug-1",
    severity: "HIGH",
    acceptanceCriteriaId: "ac-current-1",
    reproductionSteps: ["run npm test", "observe checkout failure"],
    expected: "checkout succeeds",
    actual: "checkout exits with code 1",
    evidenceArtifactIds: ["qa-evidence/commands/current.log"],
    suspectedFiles: ["src/checkout.ts"],
  }];

  assert.deepEqual(validateRoleResult(
    "QA_AGENT", report, undefined, {}, [], undefined, true, ["ac-current-1"],
  ), []);
});

test("rejects malformed or contradictory PI-v2 QA remediation requests", () => {
  const passed = completeQaReport("qa-evidence/commands/current.log");
  passed.acceptanceResults[0].criteriaId = "ac-current-1";
  passed.acceptanceResults[1].criteriaId = "ac-regression-1";
  passed.remediationRequest = {
    requested: true,
    targetRole: "CODING_AGENT",
    reason: "should not be allowed on PASSED",
    bugFindingIds: ["bug-missing"],
  };
  passed.bugFindings = [];
  const passedErrors = validateRoleResult(
    "QA_AGENT", passed, undefined, {}, [], undefined, true, ["ac-current-1"],
  );
  assert.ok(passedErrors.some((error) => error.includes("status FAILED")));
  assert.ok(passedErrors.some((error) => error.includes("unknown bug finding")));

  const declined = completeQaReport("qa-evidence/commands/current.log");
  declined.status = "FAILED";
  declined.failureCategory = "FLAKY";
  declined.retryRecommendation = "HUMAN";
  declined.acceptanceResults[0] = {
    ...declined.acceptanceResults[0], criteriaId: "ac-current-1", status: "FAILED", exitCode: 1,
  };
  declined.acceptanceResults[1].criteriaId = "ac-regression-1";
  declined.remediationRequest = {
    requested: false, targetRole: "", reason: "flaky environment", bugFindingIds: [],
  };
  declined.bugFindings = [{
    id: "bug-forged", severity: "LOW", acceptanceCriteriaId: "ac-current-1",
    reproductionSteps: ["retry"], expected: "stable", actual: "flaky",
    evidenceArtifactIds: ["qa-evidence/commands/current.log"], suspectedFiles: [],
  }];
  const declinedErrors = validateRoleResult(
    "QA_AGENT", declined, undefined, {}, [], undefined, true, ["ac-current-1"],
  );
  assert.ok(declinedErrors.some((error) => error.includes("requested=false requires bugFindings to be empty")));

  assert.deepEqual(validateRoleResult("QA_AGENT", completeQaReport("qa-evidence/commands/current.log")), []);
});

test("PI-v2 CURRENT acceptanceResults require a frozen criteriaId; REGRESSION may omit it", () => {
  const frozen = ["AC-001"];
  const report = piV2PassedQaReport();
  const missing = validateRoleResult(
    "QA_AGENT", report, undefined, {}, [], undefined, true, frozen,
  );
  assert.ok(missing.some((error) => error.includes("acceptanceResults[0].criteriaId")
      && error.includes("frozen AC-%03d")), missing.join("; "));

  report.acceptanceResults[0].criteriaId = "AC-999";
  const unknown = validateRoleResult(
    "QA_AGENT", report, undefined, {}, [], undefined, true, frozen,
  );
  assert.ok(unknown.some((error) => error.includes("not in the frozen acceptance-criteria set")),
      unknown.join("; "));

  report.acceptanceResults[0].criteriaId = "AC-001";
  assert.deepEqual(validateRoleResult(
    "QA_AGENT", report, undefined, {}, [], undefined, true, frozen,
  ), []);
});

test("loads frozen CURRENT criteriaIds from the read-only /work/input attachment", () => {
  const root = mkdtempSync(join(tmpdir(), "rd-frozen-ac-"));
  try {
    writeFileSync(join(root, "acceptance-criteria-ids.json"), JSON.stringify(["AC-001", "AC-002"]));
    assert.deepEqual(loadFrozenAcceptanceCriteriaIds(root), ["AC-001", "AC-002"]);
    mkdirSync(join(root, "attachments"));
    writeFileSync(
      join(root, "attachments", "acceptance-criteria-ids.json"),
      JSON.stringify(["AC-003"]),
    );
    // Direct file wins over attachments/.
    assert.deepEqual(loadFrozenAcceptanceCriteriaIds(root), ["AC-001", "AC-002"]);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});

test("accepts Host assertion contracts only for QA requests", () => {
  const contracts = hostAssertionContracts();
  const qaRequest = { ...request, role: "QA_AGENT", hostAssertionContracts: contracts };

  assert.equal(validateRequest(qaRequest), qaRequest);
  assert.throws(() => validateRequest({ ...request, hostAssertionContracts: contracts }), /QA_AGENT/);
  assert.throws(() => validateRequest({
    ...qaRequest,
    hostAssertionContracts: [{ scope: "CURRENT", contentHash: "not-a-hash", version: 1 }],
  }), /hostAssertionContracts/);
});

test("requires QA to echo only matching Host assertion contracts and scoped evidence", () => {
  const contracts = hostAssertionContracts();
  const base = completeQaReport("qa-evidence/commands/current.log");
  const report = {
    ...base,
    acceptanceResults: base.acceptanceResults.map((entry) => (
      entry.scope === "REGRESSION"
        ? {
          ...entry,
          logArtifactId: "qa-evidence/commands/regression.log",
          evidenceArtifactIds: ["qa-evidence/commands/regression.log"],
        }
        : entry
    )),
    hostAssertionResults: [
      {
        scope: "CURRENT",
        contentHash: contracts[0].contentHash,
        evidenceArtifactIds: ["qa-evidence/commands/current.log"],
      },
      {
        scope: "REGRESSION",
        contentHash: contracts[1].contentHash,
        evidenceArtifactIds: ["qa-evidence/commands/regression.log"],
      },
    ],
  };

  assert.deepEqual(validateRoleResult("QA_AGENT", report, undefined, {}, contracts), []);

  const missingEchoReport = { ...report };
  delete missingEchoReport.hostAssertionResults;
  const missingEchoErrors = validateRoleResult("QA_AGENT", missingEchoReport, undefined, {}, contracts);
  assert.ok(missingEchoErrors.some((error) => error.includes("hostAssertionResults is required")));

  const executableAgentFields = validateRoleResult("QA_AGENT", {
    ...report,
    hostAssertionBundle: { specs: [] },
  }, undefined, {}, contracts);
  assert.ok(executableAgentFields.some((error) => error.includes("hostAssertionBundle is not accepted")));

  const wrongScopeEvidence = validateRoleResult("QA_AGENT", {
    ...report,
    hostAssertionResults: report.hostAssertionResults.map((entry) => (
      entry.scope === "REGRESSION"
        ? { ...entry, evidenceArtifactIds: ["qa-evidence/commands/current.log"] }
        : entry
    )),
  }, undefined, {}, contracts);
  assert.ok(wrongScopeEvidence.some((error) => error.includes("REGRESSION") && error.includes("not referenced")));
});

test("rejects browser QA that collects but does not reference console and network evidence", () => {
  const report = {
    status: "PASSED",
    summary: "all acceptance criteria pass under browser validation",
    failureCategory: "NONE",
    retryRecommendation: "NONE",
    evidenceManifestArtifactId: "qa-evidence/manifest.json",
    browserValidation: {
      required: true,
      performed: true,
      decisionSource: "AUTO_DETECTION",
      baseUrl: "http://127.0.0.1:3000",
      browser: "chromium",
      viewports: ["desktop-1440x900", "mobile-390x844"],
    },
    acceptanceResults: [
      {
        criteria: "feature renders",
        scope: "CURRENT",
        command: "bash current-browser.sh",
        status: "PASSED",
        exitCode: 0,
        durationMillis: 30000,
        logArtifactId: "qa-evidence/commands/browser.log",
        evidenceArtifactIds: [
          "qa-evidence/screenshots/current-desktop.png",
          "qa-evidence/screenshots/current-mobile.png",
          "qa-evidence/traces/current.zip",
        ],
      },
      {
        criteria: "existing pages render",
        scope: "REGRESSION",
        command: "npm run build",
        status: "PASSED",
        exitCode: 0,
        durationMillis: 4000,
        logArtifactId: "qa-evidence/commands/build.log",
        evidenceArtifactIds: ["qa-evidence/commands/build.log"],
      },
    ],
  };
  const errors = validateRoleResult("QA_AGENT", report);
  assert.ok(errors.some((error) => error.includes("reference a qa-evidence/console/ log")));
  assert.ok(errors.some((error) => error.includes("reference a qa-evidence/network/ log")));
  const referenced = {
    ...report,
    acceptanceResults: report.acceptanceResults.map((item, index) => (index === 0
        ? {
          ...item,
          evidenceArtifactIds: [
            ...item.evidenceArtifactIds,
            "qa-evidence/console/current.log",
            "qa-evidence/network/current.log",
          ],
        }
        : item)),
  };
  assert.deepEqual(validateRoleResult("QA_AGENT", referenced), []);
});

test("does not require browser evidence references when browser validation was not performed", () => {
  const report = {
    status: "FAILED",
    summary: "startup timed out before any browser flow",
    failureCategory: "ENVIRONMENT",
    retryRecommendation: "HUMAN",
    evidenceManifestArtifactId: "qa-evidence/manifest.json",
    browserValidation: {
      required: true,
      performed: false,
      decisionSource: "AUTO_DETECTION",
      baseUrl: "http://127.0.0.1:3000",
      browser: "chromium",
      viewports: [],
    },
    acceptanceResults: [
      {
        criteria: "feature renders",
        scope: "CURRENT",
        command: "bash start.sh",
        status: "FAILED",
        exitCode: 1,
        durationMillis: 60000,
        logArtifactId: "qa-evidence/commands/startup.log",
        evidenceArtifactIds: ["qa-evidence/commands/startup.log"],
      },
      {
        criteria: "existing pages render",
        scope: "REGRESSION",
        command: "npm run build",
        status: "PASSED",
        exitCode: 0,
        durationMillis: 4000,
        logArtifactId: "qa-evidence/commands/build.log",
        evidenceArtifactIds: ["qa-evidence/commands/build.log"],
      },
    ],
  };
  const errors = validateRoleResult("QA_AGENT", report);
  assert.ok(!errors.some((error) => error.includes("browser validation requires acceptanceResults")));
});

test("accepts a QA manifest that covers every evidence file with matching integrity metadata", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-qa-manifest-"));
  const evidencePath = "qa-evidence/commands/current.log";
  const content = Buffer.from("command=npm test\nexitCode=0\n", "utf8");
  await writeQaEvidenceFile(root, evidencePath, content);
  await writeQaManifest(root, {
    version: 1,
    artifacts: [manifestEntry(evidencePath, content)],
  });

  const errors = await resultTool.validateQaEvidenceManifest(
    { evidenceManifestArtifactId: "qa-evidence/manifest.json" },
    root,
  );

  assert.deepEqual(errors, []);
});

test("rejects QA manifest hash mismatches and uncovered evidence files before submission", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-qa-manifest-"));
  const listedPath = "qa-evidence/commands/current.log";
  const uncoveredPath = "qa-evidence/console/browser.log";
  const content = Buffer.from("command=npm test\nexitCode=0\n", "utf8");
  await writeQaEvidenceFile(root, listedPath, content);
  await writeQaEvidenceFile(root, uncoveredPath, Buffer.from("console clean\n", "utf8"));
  await writeQaManifest(root, {
    version: 1,
    artifacts: [{
      ...manifestEntry(listedPath, content),
      sha256: "0".repeat(64),
    }],
  });

  const errors = await resultTool.validateQaEvidenceManifest(
    { evidenceManifestArtifactId: "qa-evidence/manifest.json" },
    root,
  );

  assert.ok(errors.some((error) => error.includes("sha256 does not match collected artifact")));
  assert.ok(errors.some((error) => error.includes(`does not cover collected artifact: ${uncoveredPath}`)));
});

test("rejects duplicate, self-referencing, and symlinked QA manifest entries", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-qa-manifest-"));
  const realPath = "qa-evidence/commands/current.log";
  const symlinkPath = "qa-evidence/commands/linked.log";
  const symlinkedDirectoryPath = "qa-evidence/linked-dir/outside.log";
  const content = Buffer.from("command=npm test\nexitCode=0\n", "utf8");
  await writeQaEvidenceFile(root, realPath, content);
  await symlink("current.log", join(root, symlinkPath));
  const outsideDirectory = join(root, "outside");
  await mkdir(outsideDirectory, { recursive: true });
  await writeFile(join(outsideDirectory, "outside.log"), content);
  await symlink(outsideDirectory, join(root, "qa-evidence/linked-dir"));
  await writeQaManifest(root, {
    schema: "qa-evidence/v1",
    artifacts: [
      manifestEntry(realPath, content),
      manifestEntry(realPath, content),
      manifestEntry("qa-evidence/manifest.json", content),
      manifestEntry(symlinkPath, content),
      manifestEntry(symlinkedDirectoryPath, content),
    ],
  });

  const errors = await resultTool.validateQaEvidenceManifest(
    { evidenceManifestArtifactId: "qa-evidence/manifest.json" },
    root,
  );

  assert.ok(errors.some((error) => error.includes(`duplicate artifact path: ${realPath}`)));
  assert.ok(errors.some((error) => error.includes("has an invalid evidence path: qa-evidence/manifest.json")));
  assert.ok(errors.some((error) => error.includes(`has an invalid evidence path: ${symlinkPath}`)));
  assert.ok(errors.some((error) => error.includes(`has an invalid evidence path: ${symlinkedDirectoryPath}`)));
  assert.ok(!errors.some((error) => error.includes("must contain version 1")));
});

test("rejects an invalid QA manifest through rd_submit_result before accepting the result", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-qa-submit-"));
  const evidencePath = "qa-evidence/commands/current.log";
  const content = Buffer.from("command=npm test\nexitCode=0\n", "utf8");
  await writeQaEvidenceFile(root, evidencePath, content);
  await writeQaManifest(root, {
    version: 1,
    artifacts: [{
      ...manifestEntry(evidencePath, content),
      sha256: "0".repeat(64),
    }],
  });
  let accepted = false;
  const lifecycleEvents = [];
  const tool = bridge.createResultTool({
    resultPath: join(root, "result.json"),
    outputRoot: root,
    sink: {
      async lifecycle(eventType, payload) {
        lifecycleEvents.push({ eventType, payload });
      },
    },
    context: { role: "QA_AGENT" },
    onAccepted() {
      accepted = true;
    },
  });

  await assert.rejects(
    tool.execute("call-1", { result: completeQaReport(evidencePath) }),
    /sha256 does not match collected artifact/,
  );
  assert.equal(accepted, false);
  assert.equal(lifecycleEvents.at(-1).eventType, "RESULT_REJECTED");
});

function manifestEntry(path, content) {
  return {
    path,
    bytes: content.length,
    sha256: createHash("sha256").update(content).digest("hex"),
  };
}

async function writeQaEvidenceFile(root, relativePath, content) {
  const absolutePath = join(root, relativePath);
  await mkdir(dirname(absolutePath), { recursive: true });
  await writeFile(absolutePath, content);
}

async function writeQaManifest(root, manifest) {
  await writeQaEvidenceFile(
    root,
    "qa-evidence/manifest.json",
    Buffer.from(`${JSON.stringify(manifest)}\n`, "utf8"),
  );
}

function completeQaReport(evidencePath) {
  const acceptanceResult = (criteria, scope) => ({
    criteria,
    scope,
    command: "npm test",
    status: "PASSED",
    exitCode: 0,
    durationMillis: 100,
    logArtifactId: evidencePath,
    evidenceArtifactIds: [evidencePath],
  });
  return {
    status: "PASSED",
    summary: "all required checks passed",
    failureCategory: "NONE",
    retryRecommendation: "NONE",
    evidenceManifestArtifactId: "qa-evidence/manifest.json",
    browserValidation: {
      required: false,
      performed: false,
      decisionSource: "NOT_APPLICABLE",
      baseUrl: "",
      browser: "",
      viewports: [],
    },
    acceptanceResults: [
      acceptanceResult("feature works", "CURRENT"),
      acceptanceResult("regression stays green", "REGRESSION"),
    ],
  };
}

function piV2PassedQaReport() {
  const report = completeQaReport("qa-evidence/commands/current.log");
  report.remediationRequest = {
    requested: false,
    targetRole: "",
    reason: "no product defect",
    bugFindingIds: [],
  };
  report.bugFindings = [];
  return report;
}

function hostAssertionContracts() {
  return [
    {
      scope: "CURRENT",
      contentHash: `sha256:${"a".repeat(64)}`,
      version: 1,
    },
    {
      scope: "REGRESSION",
      contentHash: `sha256:${"b".repeat(64)}`,
      version: 1,
    },
  ];
}

test("requires LOW budget confidence without historical samples for the reviewer", () => {
  const review = {
    status: "SUCCESS",
    decision: "APPROVED",
    feasibility: "CAN_DO",
    missingInformation: [],
    risks: [],
    acceptanceCoverage: ["covered"],
    budgetEstimate: {
      initialTokens: 1000,
      retryReserveTokens: 500,
      estimatedTotalTokens: 1500,
      confidence: "MEDIUM",
      basis: "model judgement",
      historicalSamples: [],
    },
  };
  assert.ok(validateRoleResult("REQUIREMENT_REVIEWER", review)
      .some((error) => error.includes("confidence must be LOW")));
  review.budgetEstimate.confidence = "LOW";
  assert.deepEqual(validateRoleResult("REQUIREMENT_REVIEWER", review), []);
});

function completeReviewerResult() {
  return {
    status: "SUCCESS",
    decision: "APPROVED",
    feasibility: "CAN_DO",
    missingInformation: [],
    risks: ["env risk"],
    acceptanceCoverage: ["merchant home shows live store data"],
    environmentNotes: [],
    budgetEstimate: {
      initialTokens: 1000,
      retryReserveTokens: 500,
      estimatedTotalTokens: 1500,
      confidence: "LOW",
      basis: "no historical samples",
      historicalSamples: [],
    },
    next_prompt: {
      targetRole: "SOLUTION_ARCHITECT",
      summary: "implement merchant admin pages",
      handoffArtifact: "handoff/next.md",
    },
  };
}

async function reviewerSubmitTool(root) {
  let accepted = false;
  const lifecycleEvents = [];
  const tool = bridge.createResultTool({
    resultPath: join(root, "result.json"),
    outputRoot: root,
    sink: {
      async lifecycle(eventType, payload) {
        lifecycleEvents.push({ eventType, payload });
      },
    },
    context: { role: "REQUIREMENT_REVIEWER" },
    onAccepted() {
      accepted = true;
    },
  });
  return { tool, accepted: () => accepted, lifecycleEvents };
}

test("accepts a reviewer rd_submit_result when OpenAI-compatible providers stringify result", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-reviewer-submit-"));
  const { tool, accepted, lifecycleEvents } = await reviewerSubmitTool(root);
  const payload = completeReviewerResult();
  const outcome = await tool.execute("call-1", { result: JSON.stringify(payload) });
  assert.equal(accepted(), true);
  assert.equal(outcome.terminate, true);
  assert.equal(lifecycleEvents.at(-1).eventType, "RESULT_SUBMITTED");
  assert.deepEqual(JSON.parse(await readFile(join(root, "result.json"), "utf8")).decision, "APPROVED");
});

test("accepts a double-encoded reviewer result string from the same provider path", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-reviewer-double-"));
  const { tool, accepted } = await reviewerSubmitTool(root);
  await tool.execute("call-1", { result: JSON.stringify(JSON.stringify(completeReviewerResult())) });
  assert.equal(accepted(), true);
});

test("accepts a reviewer role object that carries its own role status", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-reviewer-object-"));
  const { tool, accepted } = await reviewerSubmitTool(root);
  await tool.execute("call-1", { result: completeReviewerResult() });
  assert.equal(accepted(), true);
});

test("mirrors the coding structured-result contract at submission time", () => {
  const errors = validateRoleResult("CODING_AGENT", { status: "SUCCESS", summary: "done" });
  assert.ok(errors.some((error) => error.includes("changedFiles")));
  assert.ok(errors.some((error) => error.includes("prBody")));
  const complete = {
    status: "SUCCESS",
    summary: "done",
    changedFiles: ["src/a.ts"],
    testCommands: ["npm test"],
    testStatus: "PASSED",
    riskLevel: "LOW",
    prBody: "change with evidence",
    needHumanAction: false,
  };
  assert.deepEqual(validateRoleResult("CODING_AGENT", complete), []);
});

test("accepts only verified extension resources under the input root", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-resource-"));
  const extensionRoot = join(root, "extensions");
  await mkdir(extensionRoot, { recursive: true });
  const extensionPath = join(extensionRoot, "review.mjs");
  await writeFile(extensionPath, "export default () => {};\n", "utf8");
  const digest = await hashResourcePath(extensionPath);
  const manifest = {
    protocol: "rd-agent-resource-manifest/v1",
    extensionSetId: "set-1",
    extensionSetVersion: 3,
    verificationStatus: "VERIFIED",
    resources: [{
      kind: "extension",
      path: extensionPath,
      status: "VERIFIED",
      sha256: digest,
    }],
  };

  const verified = await validateResourceManifest(manifest, { root: extensionRoot });
  assert.deepEqual(verified.extensionPaths, [extensionPath]);
  assert.equal(verified.manifest.extensionSetVersion, 3);
});

test("fails closed for an unverified or escaped extension resource", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-resource-"));
  const extensionRoot = join(root, "extensions");
  await mkdir(extensionRoot, { recursive: true });
  const extensionPath = join(extensionRoot, "review.mjs");
  await writeFile(extensionPath, "export default () => {};\n", "utf8");
  const digest = await hashResourcePath(extensionPath);
  const baseManifest = {
    protocol: "rd-agent-resource-manifest/v1",
    extensionSetId: "set-1",
    extensionSetVersion: 1,
    verificationStatus: "VERIFIED",
    resources: [{
      kind: "extension",
      path: extensionPath,
      status: "VERIFIED",
      sha256: digest,
    }],
  };
  await assert.rejects(
    validateResourceManifest({ ...baseManifest, verificationStatus: "PENDING" }, { root: extensionRoot }),
  );
  await assert.rejects(
    validateResourceManifest({
      ...baseManifest,
      resources: [{ ...baseManifest.resources[0], path: join(root, "outside.mjs") }],
    }, { root: extensionRoot }),
  );
});

test("writes audit-only runtime context manifest after resource discovery", async () => {
  const root = await mkdtemp(join(tmpdir(), "rd-pi-runtime-manifest-"));
  const output = join(root, "output");
  await mkdir(output, { recursive: true });
  const manifest = await writeRuntimeContextManifest({
    request: {
      ...request,
      repoPath: root,
      attemptNo: 1,
    },
    paths: {
      runtimeContextManifest: join(output, "runtime-context-manifest.json"),
    },
    contextFiles: [
      { path: join(root, "AGENTS.md"), sha256: "deadbeef", bytes: 12 },
    ],
    inputManifestHash: "sha256:abc",
  });
  const persisted = JSON.parse(await readFile(join(output, "runtime-context-manifest.json"), "utf8"));
  assert.equal(manifest.mode, "LEGACY_OBSERVE_ONLY");
  assert.equal(persisted.observedFiles.length, 1);
  assert.equal(persisted.observedFiles[0].path, "AGENTS.md");
  assert.equal(persisted.observedFiles[0].trustDecision, "LOADED");
  assert.equal(persisted.inputManifestHash, "sha256:abc");
});

test("docs-only classifier matches the host allowlist and fails closed", () => {
  assert.equal(
    classifyDocsOnlyChange(["README.md", "docs/guide.md", "docs/logo.svg"]),
    DOCS_ONLY_DECISION.DOCS_ONLY,
  );
  assert.equal(
    classifyDocsOnlyChange(["README.md", "src/app/page.tsx"]),
    DOCS_ONLY_DECISION.NOT_DOCS_ONLY,
  );
  assert.equal(classifyDocsOnlyChange(["package.json"]), DOCS_ONLY_DECISION.NOT_DOCS_ONLY);
  assert.equal(classifyDocsOnlyChange(["package-lock.json"]), DOCS_ONLY_DECISION.NOT_DOCS_ONLY);
  assert.equal(classifyDocsOnlyChange(null), DOCS_ONLY_DECISION.UNDETERMINABLE);
  assert.equal(classifyDocsOnlyChange([]), DOCS_ONLY_DECISION.UNDETERMINABLE);
});

test("accepts docs-only QA without browser evidence when changed files are docs-only", () => {
  const report = {
    ...completeQaReport("qa-evidence/commands/current.log"),
    summary: "docs-only candidate verified without browser regression",
    browserValidation: {
      required: false,
      performed: false,
      decisionSource: "DOCS_ONLY",
      baseUrl: "",
      browser: "chromium",
      viewports: [],
    },
  };
  assert.deepEqual(
    validateRoleResult("QA_AGENT", report, undefined, {}, [], ["README.md", "docs/smoke.md"]),
    [],
  );
});

test("rejects docs-only QA claim when changed files include source or package.json", () => {
  const report = {
    ...completeQaReport("qa-evidence/commands/current.log"),
    browserValidation: {
      required: false,
      performed: false,
      decisionSource: "DOCS_ONLY",
      baseUrl: "",
      browser: "chromium",
      viewports: [],
    },
  };
  const sourceErrors = validateRoleResult(
    "QA_AGENT",
    report,
    undefined,
    {},
    [],
    ["README.md", "src/app/page.tsx"],
  );
  const packageErrors = validateRoleResult(
    "QA_AGENT",
    report,
    undefined,
    {},
    [],
    ["package.json"],
  );
  assert.ok(sourceErrors.some((error) => error.includes("docs-only") && error.includes("full browser")));
  assert.ok(packageErrors.some((error) => error.includes("docs-only") && error.includes("full browser")));
});

test("fails closed when docs-only claim lacks a determinable changed-file set", () => {
  const report = {
    ...completeQaReport("qa-evidence/commands/current.log"),
    browserValidation: {
      required: false,
      performed: false,
      decisionSource: "DOCS_ONLY",
      baseUrl: "",
      browser: "chromium",
      viewports: [],
    },
  };
  const missing = validateRoleResult("QA_AGENT", report, undefined, {}, [], null);
  const empty = validateRoleResult("QA_AGENT", report, undefined, {}, [], []);
  assert.ok(missing.some((error) => error.includes("docs-only") && error.includes("undeterminable")));
  assert.ok(empty.some((error) => error.includes("docs-only") && error.includes("undeterminable")));
});
