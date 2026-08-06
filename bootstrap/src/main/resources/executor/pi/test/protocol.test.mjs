import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";

import { EventNormalizer } from "../src/event-normalizer.mjs";
import {
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
} from "../src/protocol.mjs";
import { validateResult, validateRoleResult } from "../src/result-tool.mjs";
import * as resultTool from "../src/result-tool.mjs";
import {
  hashResourcePath,
  validateResourceManifest,
} from "../src/resource-loader.mjs";
import { EventSink, createObservabilityExtension, executionPrompt, writeRuntimeContextManifest } from "../src/rd-pi-bridge.mjs";
import * as bridge from "../src/rd-pi-bridge.mjs";

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

test("fails closed when the credential relay rejects lease redemption", async () => {
  assert.equal(typeof bridge.redeemPiCredentialLease, "function");
  let error;
  try {
    await bridge.redeemPiCredentialLease({
      env: {
        RD_PI_CREDENTIAL_RELAY_URL: "http://host.test/internal/pi/credential-relay/redeem",
        RD_PI_CREDENTIAL_LEASE: "pcl_test",
        RD_PI_CREDENTIAL_RELAY_TASK_ID: "task-1",
        RD_PI_CREDENTIAL_RELAY_STAGE_RUN_ID: "stage-1",
        RD_PI_CREDENTIAL_RELAY_PROVIDER_ID: "provider-1",
      },
      fetchImpl: async () => ({
        ok: false,
        status: 401,
        text: async () => JSON.stringify({ credential: "super-secret" }),
      }),
    });
  } catch (caught) {
    error = caught;
  }
  assert.ok(error instanceof Error);
  assert.match(error.message, /credential relay redemption failed/);
  assert.doesNotMatch(error.message, /super-secret/);
});

test("redeems a bound lease through the configured relay without sending the credential", async () => {
  let requestUrl;
  let requestInit;
  const credential = await bridge.redeemPiCredentialLease({
    env: {
      RD_PI_CREDENTIAL_RELAY_URL: "http://host.test/internal/pi/credential-relay/redeem",
      RD_PI_CREDENTIAL_LEASE: "pcl_test",
      RD_PI_CREDENTIAL_RELAY_TASK_ID: "task-1",
      RD_PI_CREDENTIAL_RELAY_STAGE_RUN_ID: "stage-1",
      RD_PI_CREDENTIAL_RELAY_PROVIDER_ID: "provider-1",
    },
    fetchImpl: async (url, init) => {
      requestUrl = url;
      requestInit = init;
      return {
        ok: true,
        status: 200,
        text: async () => JSON.stringify({ credential: "super-secret" }),
      };
    },
  });

  assert.equal(credential, "super-secret");
  assert.equal(requestUrl, "http://host.test/internal/pi/credential-relay/redeem");
  assert.equal(requestInit.method, "POST");
  assert.equal(requestInit.headers.Authorization, "Bearer pcl_test");
  assert.deepEqual(JSON.parse(requestInit.body), {
    taskId: "task-1",
    stageRunId: "stage-1",
    providerId: "provider-1",
  });
  assert.doesNotMatch(JSON.stringify({ requestUrl, requestInit }), /super-secret/);
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

test("validates optional host assertion bundle fields without requiring a bundle", () => {
  const report = completeQaReport("qa-evidence/commands/current.log");
  assert.deepEqual(validateRoleResult("QA_AGENT", report), []);

  const withBundle = {
    ...report,
    hostAssertionBundle: {
      contentHash: `sha256:${"a".repeat(64)}`,
      specs: [{
        id: "json-1",
        assertionType: "HTTP_JSONPATH",
        target: "$.ok",
        operator: "eq",
        expected: "true",
      }],
    },
  };
  assert.deepEqual(validateRoleResult("QA_AGENT", withBundle), []);

  const malformed = {
    ...withBundle,
    hostAssertionBundle: { specs: "not-an-array" },
  };
  const errors = validateRoleResult("QA_AGENT", malformed);
  assert.ok(errors.some((error) => error.includes("contentHash")));
  assert.ok(errors.some((error) => error.includes("specs must be an array")));
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

test("requires LOW budget confidence without historical samples for the reviewer", () => {
  const review = {
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
