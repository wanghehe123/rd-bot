import test from "node:test";
import assert from "node:assert/strict";

import { EventNormalizer } from "../src/event-normalizer.mjs";
import {
  parseJsonLine,
  redact,
  validateRequest,
} from "../src/protocol.mjs";
import { validateResult } from "../src/result-tool.mjs";
import {
  hashResourcePath,
  validateResourceManifest,
} from "../src/resource-loader.mjs";

import { mkdtemp, mkdir, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

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
  credentialEnvironmentVariable: "ANTHROPIC_API_KEY",
  toolPolicy: { allow: ["read", "bash", "edit", "write", "rd_submit_result"] },
};

test("validates the fixed one-shot request contract", () => {
  assert.equal(validateRequest(request), request);
  assert.throws(() => validateRequest({ ...request, credentialEnvironmentVariable: "secret-value" }));
  assert.throws(() => validateRequest({ ...request, repoPath: "/tmp/repo" }));
});

test("rejects oversized or malformed JSON lines", () => {
  assert.deepEqual(parseJsonLine("{\"ok\":true}"), { ok: true });
  assert.throws(() => parseJsonLine("{"));
  assert.throws(() => parseJsonLine("x".repeat(20), 8));
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

test("requires a versioned structured result", () => {
  assert.deepEqual(validateResult({ status: "SUCCESS", summary: "done" }), {
    status: "SUCCESS",
    summary: "done",
  });
  assert.throws(() => validateResult({ status: "OK", summary: "done" }));
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
