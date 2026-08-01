package com.wish.rd.rag.project.agent.model;

/** Audit reference to an upstream handoff artifact consumed by the current stage. */
public record HandoffManifestEntry(
    String artifactId,
    String artifactUri,
    String contentHash,
    String sourceRole,
    String targetRole
) {

  public HandoffManifestEntry {
    artifactId = artifactId == null ? "" : artifactId.strip();
    artifactUri = artifactUri == null ? "" : artifactUri.strip();
    contentHash = normalizeHash(contentHash);
    sourceRole = sourceRole == null ? "" : sourceRole.strip().toUpperCase();
    targetRole = targetRole == null ? "" : targetRole.strip().toUpperCase();
  }

  /** Backward-compatible constructor when only URI and hash are known. */
  public HandoffManifestEntry(String artifactId, String contentHash) {
    this(artifactId, artifactId, contentHash, "", "");
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
