package com.wish.rd.rag.project.agent.model;

/** Exact host prompt audit metadata stored alongside the input manifest. */
public record RoleExecutionPromptAudit(
    String contentHash,
    long bytes,
    long estimatedTokens,
    String estimatorVersion
) {

  public RoleExecutionPromptAudit {
    contentHash = normalizeHash(contentHash);
    bytes = Math.max(0L, bytes);
    estimatedTokens = Math.max(0L, estimatedTokens);
    estimatorVersion = estimatorVersion == null || estimatorVersion.isBlank()
        ? "chars/4-v1"
        : estimatorVersion.strip();
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
