package com.wish.rd.rag.project.agent.model;

/** Audit reference to an upstream handoff artifact consumed by the current stage. */
public record HandoffManifestEntry(String artifactId, String contentHash) {

  public HandoffManifestEntry {
    artifactId = artifactId == null ? "" : artifactId.strip();
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
