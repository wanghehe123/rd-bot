package com.wish.rd.rag.project.agent.model;

/** Observed runtime context file decision recorded by the bridge or host validator. */
public record RuntimeContextFileDecision(
    String path,
    String contentHash,
    long bytes,
    int loadOrder,
    String scope,
    String trustDecision,
    String rejectReason
) {

  public RuntimeContextFileDecision {
    path = path == null ? "" : path.strip().replace('\\', '/');
    contentHash = normalizeHash(contentHash);
    bytes = Math.max(0L, bytes);
    loadOrder = Math.max(0, loadOrder);
    scope = scope == null ? "" : scope.strip();
    trustDecision = trustDecision == null ? "" : trustDecision.strip();
    rejectReason = rejectReason == null ? "" : rejectReason.strip();
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
