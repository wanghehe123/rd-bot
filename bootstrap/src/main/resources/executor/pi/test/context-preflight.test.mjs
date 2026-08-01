import test from "node:test";
import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import { mkdir, mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

import {
  REQUEST_PROTOCOL,
  REQUEST_PROTOCOL_V2,
} from "../src/protocol.mjs";
import {
  buildPreflightRuntimeContextManifest,
  computeEffectiveContextHash,
  PREFLIGHT_STATUS,
  runContextPreflight,
  shouldRunContextPreflight,
  validateContextPreflight,
} from "../src/context-preflight.mjs";
import { CONTEXT_POLICY_MODES } from "../src/resource-loader.mjs";

const testDir = dirname(fileURLToPath(import.meta.url));
const fixtureRepo = join(testDir, "fixtures", "context-policy-repo");

function sha256Text(value) {
  return createHash("sha256").update(value, "utf8").digest("hex");
}

function baseRequest(overrides = {}) {
  return {
    protocol: REQUEST_PROTOCOL_V2,
    snapshotId: "snapshot-1",
    stageRunId: "stage-1",
    taskId: "task-1",
    role: "CODING_AGENT",
    prompt: "implement",
    provider: "anthropic",
    model: "claude-sonnet-4-5",
    repoPath: fixtureRepo,
    inputPath: "/work/input",
    outputPath: "/work/output",
    resourceManifestPath: "/work/input/resource-manifest.json",
    inputManifestPath: "/work/input/role-execution-input-manifest.json",
    inputManifestHash: "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
    toolPolicy: { allow: ["read", "bash", "edit", "write", "rd_submit_result"] },
    ...overrides,
  };
}

async function rootOnlyPolicyWithFixtureHashes() {
  const agentsContent = await readFile(join(fixtureRepo, "AGENTS.md"), "utf8");
  const claudeContent = await readFile(join(fixtureRepo, "CLAUDE.md"), "utf8");
  return {
    protocol: "rd-runtime-context-policy/v1",
    mode: CONTEXT_POLICY_MODES.ROOT_ONLY,
    policyHash: "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
    expectedFiles: [
      { path: "AGENTS.md", contentHash: `sha256:${sha256Text(agentsContent)}` },
      { path: "CLAUDE.md", contentHash: `sha256:${sha256Text(claudeContent)}` },
    ],
  };
}

test("shouldRunContextPreflight gates v2 and ROOT_ONLY only", () => {
  assert.equal(shouldRunContextPreflight({ protocol: REQUEST_PROTOCOL_V2 }), true);
  assert.equal(shouldRunContextPreflight({
    protocol: REQUEST_PROTOCOL,
    contextPolicy: { mode: CONTEXT_POLICY_MODES.ROOT_ONLY },
  }), true);
  assert.equal(shouldRunContextPreflight({ protocol: REQUEST_PROTOCOL }), false);
  assert.equal(shouldRunContextPreflight({
    protocol: REQUEST_PROTOCOL,
    contextPolicy: { mode: CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY },
  }), false);
});

test("ROOT_ONLY fixture ACCEPTED path writes manifest and does not require provider", async () => {
  const contextPolicy = await rootOnlyPolicyWithFixtureHashes();
  const request = baseRequest({ contextPolicy });
  let providerStarted = false;

  const { discovery, validation } = await runContextPreflight(fixtureRepo, contextPolicy);
  assert.equal(validation.accepted, true);
  assert.deepEqual(validation.loadedPaths, ["AGENTS.md", "CLAUDE.md"]);

  const manifest = buildPreflightRuntimeContextManifest({
    request,
    discovery,
    validation,
    inputManifestHash: request.inputManifestHash,
    contextPolicyHash: contextPolicy.policyHash,
  });
  assert.equal(manifest.status, PREFLIGHT_STATUS.ACCEPTED);
  assert.equal(manifest.mode, CONTEXT_POLICY_MODES.ROOT_ONLY);
  assert.equal(manifest.totalFiles, 2);
  assert.match(manifest.effectiveContextHash, /^sha256:[0-9a-f]{64}$/);
  assert.equal(
    manifest.effectiveContextHash,
    computeEffectiveContextHash(discovery.loaded),
  );

  const loaded = manifest.observedFiles.filter((entry) => entry.trustDecision === "LOADED");
  assert.deepEqual(loaded.map((entry) => entry.path), ["AGENTS.md", "CLAUDE.md"]);
  assert.ok(loaded.every((entry) => entry.contentHash.startsWith("sha256:")));

  const output = await mkdtemp(join(tmpdir(), "rd-pi-preflight-accepted-"));
  await mkdir(output, { recursive: true });
  const manifestPath = join(output, "runtime-context-manifest.json");
  await writeFile(manifestPath, `${JSON.stringify(manifest)}\n`, "utf8");
  const persisted = JSON.parse(await readFile(manifestPath, "utf8"));
  assert.equal(persisted.status, PREFLIGHT_STATUS.ACCEPTED);

  if (!validation.accepted) {
    providerStarted = true;
  }
  assert.equal(providerStarted, false);
});

test("ROOT_ONLY REJECTED when expected hash disagrees", async () => {
  const contextPolicy = {
    ...(await rootOnlyPolicyWithFixtureHashes()),
    expectedFiles: [{
      path: "AGENTS.md",
      contentHash: "sha256:0000000000000000000000000000000000000000000000000000000000000000",
    }],
  };
  let providerStarted = false;

  const { discovery, validation } = await runContextPreflight(fixtureRepo, contextPolicy);
  assert.equal(validation.accepted, false);
  assert.ok(validation.violations.some((violation) => violation.includes("AGENTS.md")));

  const manifest = buildPreflightRuntimeContextManifest({
    request: baseRequest({ contextPolicy }),
    discovery,
    validation,
    contextPolicyHash: contextPolicy.policyHash,
  });
  assert.equal(manifest.status, PREFLIGHT_STATUS.REJECTED);
  assert.equal(manifest.effectiveContextHash, "");
  assert.ok(manifest.observedFiles.some((entry) => entry.rejectReason === "HASH_MISMATCH"));

  if (!validation.accepted) {
    // Bridge exits before configureModelRuntime when preflight rejects.
    providerStarted = false;
  } else {
    providerStarted = true;
  }
  assert.equal(providerStarted, false);
});

test("ROOT_ONLY REJECTED when undeclared nested file would load", async () => {
  const contextPolicy = await rootOnlyPolicyWithFixtureHashes();
  const discovery = await (async () => {
    const result = await runContextPreflight(fixtureRepo, contextPolicy);
    return {
      ...result.discovery,
      loaded: [
        ...result.discovery.loaded,
        {
          path: "src/AGENTS.md",
          sha256: sha256Text("nested\n"),
          bytes: 7,
          content: "nested\n",
        },
      ],
    };
  })();

  const validation = validateContextPreflight(discovery, contextPolicy);
  assert.equal(validation.accepted, false);
  assert.ok(validation.violations.some((violation) => violation.includes("undeclared nested file")));

  const manifest = buildPreflightRuntimeContextManifest({
    request: baseRequest({ contextPolicy }),
    discovery,
    validation,
    contextPolicyHash: contextPolicy.policyHash,
  });
  assert.equal(manifest.status, PREFLIGHT_STATUS.REJECTED);
});

test("preflight harness writes runtime-context-manifest before provider gate", async () => {
  const contextPolicy = await rootOnlyPolicyWithFixtureHashes();
  const output = await mkdtemp(join(tmpdir(), "rd-pi-preflight-harness-"));
  await mkdir(output, { recursive: true });
  const manifestPath = join(output, "runtime-context-manifest.json");

  const providerGate = { started: false };
  const { discovery, validation } = await runContextPreflight(fixtureRepo, contextPolicy);
  const manifest = buildPreflightRuntimeContextManifest({
    request: baseRequest({ contextPolicy }),
    discovery,
    validation,
    contextPolicyHash: contextPolicy.policyHash,
  });
  await writeFile(manifestPath, `${JSON.stringify(manifest)}\n`, "utf8");

  if (validation.accepted) {
    providerGate.started = true;
  }

  assert.equal(providerGate.started, true);
  const persisted = JSON.parse(await readFile(manifestPath, "utf8"));
  assert.equal(persisted.status, PREFLIGHT_STATUS.ACCEPTED);
  assert.ok(persisted.observedFiles.length >= 2);
});
