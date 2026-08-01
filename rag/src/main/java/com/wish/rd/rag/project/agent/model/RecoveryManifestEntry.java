package com.wish.rd.rag.project.agent.model;

/** Audit reference to recovery feedback injected before dispatch. */
public record RecoveryManifestEntry(
    String sourceStageRunId,
    String contentHash,
    int sourceAttemptNo,
    String reason
) {

  public static final int UNKNOWN_ATTEMPT = 0;

  public RecoveryManifestEntry {
    sourceStageRunId = sourceStageRunId == null ? "" : sourceStageRunId.strip();
    contentHash = normalizeHash(contentHash);
    sourceAttemptNo = Math.max(UNKNOWN_ATTEMPT, sourceAttemptNo);
    reason = reason == null ? "" : reason.strip();
  }

  /** Backward-compatible constructor without attempt/reason metadata. */
  public RecoveryManifestEntry(String sourceStageRunId, String contentHash) {
    this(sourceStageRunId, contentHash, UNKNOWN_ATTEMPT, "");
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
