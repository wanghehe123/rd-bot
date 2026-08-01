package com.wish.rd.rag.project.agent.model;

import java.util.List;

/**
 * Observed runtime context loading manifest produced after resource discovery.
 *
 * <p>Phase 0 is audit-only and references the frozen input manifest hash when available.
 */
public record RuntimeContextManifest(
    int schemaVersion,
    String protocol,
    String taskId,
    String stageRunId,
    String role,
    int attemptNo,
    RuntimeContextPolicyMode mode,
    String runtime,
    String provider,
    String model,
    String executionProfileSnapshotId,
    String inputManifestHash,
    String contextPolicyHash,
    String generatedAt,
    List<RuntimeContextFileDecision> observedFiles,
    int totalFiles,
    long totalBytes,
    String effectiveContextHash,
    String status
) {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final String PROTOCOL = "rd-runtime-context-manifest/v1";

  public RuntimeContextManifest {
    schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
    protocol = protocol == null || protocol.isBlank() ? PROTOCOL : protocol.strip();
    taskId = requireText(taskId, "taskId");
    stageRunId = requireText(stageRunId, "stageRunId");
    role = requireText(role, "role").toUpperCase();
    attemptNo = Math.max(1, attemptNo);
    mode = mode == null ? RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY : mode;
    runtime = runtime == null ? "" : runtime.strip();
    provider = provider == null ? "" : provider.strip();
    model = model == null ? "" : model.strip();
    executionProfileSnapshotId = executionProfileSnapshotId == null ? "" : executionProfileSnapshotId.strip();
    inputManifestHash = normalizeHash(inputManifestHash);
    contextPolicyHash = normalizeHash(contextPolicyHash);
    generatedAt = generatedAt == null ? "" : generatedAt.strip();
    observedFiles = observedFiles == null ? List.of() : List.copyOf(observedFiles);
    totalFiles = Math.max(0, totalFiles);
    totalBytes = Math.max(0L, totalBytes);
    effectiveContextHash = normalizeHash(effectiveContextHash);
    status = status == null || status.isBlank() ? "OBSERVED" : status.strip();
  }

  public String manifestHash() {
    return AgentManifestCanonicalJson.manifestHash(this);
  }

  public String canonicalJson() {
    return AgentManifestCanonicalJson.canonicalJson(this);
  }

  private static String requireText(String value, String fieldName) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
