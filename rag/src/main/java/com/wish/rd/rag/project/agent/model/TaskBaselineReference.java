package com.wish.rd.rag.project.agent.model;

/** Immutable reference to the task baseline package bound to a stage attempt. */
public record TaskBaselineReference(String artifactId, String contentHash) {

  public TaskBaselineReference {
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
