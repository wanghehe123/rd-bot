import { createHash } from "node:crypto";

import { resolve } from "node:path";

import { REQUEST_PROTOCOL_V2 } from "./protocol.mjs";
import { CONTEXT_POLICY_MODES, discoverContextFiles } from "./resource-loader.mjs";

export const PREFLIGHT_STATUS = Object.freeze({
  ACCEPTED: "ACCEPTED",
  REJECTED: "REJECTED",
});

/** True when the bridge must run offline context preflight before provider dispatch. */
export function shouldRunContextPreflight(request) {
  if (!request || typeof request !== "object") return false;
  if (request.protocol === REQUEST_PROTOCOL_V2) return true;
  return request.contextPolicy?.mode === CONTEXT_POLICY_MODES.ROOT_ONLY;
}

/** Discover repo context files and validate them against the frozen policy. */
export async function runContextPreflight(repoPath, contextPolicy) {
  const discovery = await discoverContextFiles(repoPath, {
    mode: contextPolicy.mode,
    expectedFiles: contextPolicy.expectedFiles ?? [],
  });
  const validation = validateContextPreflight(discovery, contextPolicy);
  return { discovery, validation };
}

/** Compare discovered load set/order/hashes against the frozen policy expectations. */
export function validateContextPreflight(discovery, contextPolicy) {
  const violations = [];
  const mode = contextPolicy?.mode ?? CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY;
  const expectedFiles = Array.isArray(contextPolicy?.expectedFiles) ? contextPolicy.expectedFiles : [];
  const loadedPaths = discovery.loaded.map((entry) => entry.path);

  if (mode === CONTEXT_POLICY_MODES.ROOT_ONLY) {
    for (const loaded of discovery.loaded) {
      if (loaded.path.includes("/")) {
        violations.push(`undeclared nested file loaded under ROOT_ONLY: ${loaded.path}`);
      }
    }
  }

  if (expectedFiles.length > 0) {
    const expectedPathSet = new Set(expectedFiles.map((entry) => normalizeRepoPath(entry.path)));
    for (const expected of expectedFiles) {
      const path = normalizeRepoPath(expected.path);
      const loaded = discovery.loaded.find((entry) => entry.path === path);
      if (!loaded) {
        violations.push(`expected file not loaded: ${path}`);
        continue;
      }
      if (expected.contentHash) {
        const expectedHash = normalizeSha256(expected.contentHash);
        if (expectedHash !== loaded.sha256) {
          violations.push(`hash mismatch for ${path}`);
        }
      }
    }
    for (const loaded of discovery.loaded) {
      if (!expectedPathSet.has(loaded.path)) {
        violations.push(`undeclared file loaded: ${loaded.path}`);
      }
    }
  }

  const expectedOrder = expectedFiles.length > 0
    ? expectedFiles.map((entry) => normalizeRepoPath(entry.path))
    : loadedPaths;
  if (violations.length === 0 && expectedOrder.length > 0 && !arraysEqual(loadedPaths, expectedOrder)) {
    violations.push(`loaded file order mismatch: expected ${expectedOrder.join(",")} got ${loadedPaths.join(",")}`);
  }

  return Object.freeze({
    accepted: violations.length === 0,
    violations: Object.freeze([...violations]),
    loadedPaths: Object.freeze([...loadedPaths]),
  });
}

/** Build a discovery-shaped load set from post-reload resource-loader metadata. */
export function buildDiscoveryFromContextFiles(contextFiles, mode, repoPath) {
  const root = resolve(repoPath || "/work/repo");
  const loaded = (contextFiles ?? []).map((file) => {
    const absolutePath = resolve(file.path ?? "");
    const relativePath = absolutePath.startsWith(`${root}/`)
      ? absolutePath.slice(root.length + 1).replace(/\\/g, "/")
      : normalizeRepoPath(file.path);
    return {
      path: relativePath,
      sha256: normalizeSha256(file.sha256),
      bytes: file.bytes ?? 0,
    };
  });
  return Object.freeze({
    mode: mode ?? CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY,
    loaded: Object.freeze(loaded),
    rejected: Object.freeze([]),
  });
}

/**
 * Validate the post-reload loaded context against policy and, under ROOT_ONLY,
 * against the preflight-accepted load set.
 */
export function validatePostReloadContext(discovery, contextPolicy, preflightValidation) {
  const validation = validateContextPreflight(discovery, contextPolicy);
  if (!validation.accepted) {
    return validation;
  }
  const mode = contextPolicy?.mode ?? CONTEXT_POLICY_MODES.LEGACY_OBSERVE_ONLY;
  if (mode === CONTEXT_POLICY_MODES.ROOT_ONLY && preflightValidation?.accepted) {
    const preflightPaths = [...preflightValidation.loadedPaths];
    const postReloadPaths = discovery.loaded.map((entry) => entry.path);
    if (!arraysEqual(preflightPaths, postReloadPaths)) {
      return Object.freeze({
        accepted: false,
        violations: Object.freeze([
          `post-reload loaded set mismatch: preflight [${preflightPaths.join(",")}] vs reload [${postReloadPaths.join(",")}]`,
        ]),
        loadedPaths: Object.freeze([...postReloadPaths]),
      });
    }
  }
  return validation;
}

/** Build the runtime context manifest artifact for preflight (ACCEPTED or REJECTED). */
export function buildPreflightRuntimeContextManifest({
  request,
  discovery,
  validation,
  inputManifestHash = "",
  contextPolicyHash = "",
}) {
  const observedFiles = [];
  let loadOrder = 1;
  for (const loaded of discovery.loaded) {
    observedFiles.push({
      path: loaded.path,
      contentHash: formatSha256(loaded.sha256),
      bytes: loaded.bytes ?? 0,
      loadOrder: loadOrder++,
      scope: "REPO",
      trustDecision: "LOADED",
      rejectReason: "",
    });
  }
  for (const rejected of discovery.rejected) {
    observedFiles.push({
      path: rejected.path,
      contentHash: rejected.sha256 ? formatSha256(rejected.sha256) : "",
      bytes: rejected.bytes ?? 0,
      loadOrder: 0,
      scope: "REPO",
      trustDecision: "REJECTED",
      rejectReason: rejected.reason ?? "",
    });
  }

  const loadedOnly = discovery.loaded;
  const totalBytes = loadedOnly.reduce((sum, entry) => sum + (entry.bytes ?? 0), 0);
  const status = validation.accepted ? PREFLIGHT_STATUS.ACCEPTED : PREFLIGHT_STATUS.REJECTED;
  return {
    schemaVersion: 1,
    protocol: "rd-runtime-context-manifest/v1",
    taskId: request.taskId,
    stageRunId: request.stageRunId,
    role: request.role,
    attemptNo: request.attemptNo ?? 1,
    mode: discovery.mode,
    runtime: "PI",
    provider: request.provider ?? "",
    model: request.model ?? "",
    executionProfileSnapshotId: request.snapshotId ?? "",
    inputManifestHash: inputManifestHash || "",
    contextPolicyHash: contextPolicyHash || "",
    generatedAt: new Date().toISOString(),
    observedFiles,
    totalFiles: loadedOnly.length,
    totalBytes,
    effectiveContextHash: validation.accepted ? computeEffectiveContextHash(loadedOnly) : "",
    status,
  };
}

export function computeEffectiveContextHash(loadedFiles) {
  const digest = createHash("sha256");
  for (const file of loadedFiles) {
    digest.update(file.path, "utf8");
    digest.update("\0", "utf8");
    digest.update(normalizeSha256(file.sha256), "utf8");
    digest.update("\n", "utf8");
  }
  return `sha256:${digest.digest("hex")}`;
}

function formatSha256(value) {
  const normalized = normalizeSha256(value);
  return normalized ? `sha256:${normalized}` : "";
}

function normalizeSha256(value) {
  const normalized = String(value ?? "").trim().toLowerCase();
  return normalized.startsWith("sha256:") ? normalized.slice(7) : normalized;
}

function normalizeRepoPath(value) {
  return String(value ?? "").replace(/\\/g, "/").replace(/^\/+/, "");
}

function arraysEqual(left, right) {
  return left.length === right.length && left.every((value, index) => value === right[index]);
}
