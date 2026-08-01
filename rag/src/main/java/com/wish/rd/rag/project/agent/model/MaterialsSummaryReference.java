package com.wish.rd.rag.project.agent.model;

/** Hash-only summary of task materials included in prompt construction. */
public record MaterialsSummaryReference(String contentHash, int materialCount) {

  public MaterialsSummaryReference {
    contentHash = normalizeHash(contentHash);
    materialCount = Math.max(0, materialCount);
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
