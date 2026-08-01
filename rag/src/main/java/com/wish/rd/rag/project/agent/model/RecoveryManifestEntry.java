package com.wish.rd.rag.project.agent.model;

/** Audit reference to recovery feedback injected before dispatch. */
public record RecoveryManifestEntry(String sourceStageRunId, String contentHash) {

  public RecoveryManifestEntry {
    sourceStageRunId = sourceStageRunId == null ? "" : sourceStageRunId.strip();
    contentHash = normalizeHash(contentHash);
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
