package com.wish.rd.rag.project.agent.model;

/** Audit entry for one evidence item included in the role execution input manifest. */
public record RoleExecutionEvidenceEntry(
    String sourceId,
    String sourceType,
    String factKind,
    String contentHash,
    String excerptHash,
    boolean truncated,
    String selectedReason
) {

  public RoleExecutionEvidenceEntry {
    sourceId = safe(sourceId);
    sourceType = safe(sourceType);
    factKind = safe(factKind).isBlank() ? "DECLARED" : safe(factKind);
    contentHash = normalizeHash(contentHash);
    excerptHash = normalizeHash(excerptHash);
    selectedReason = safe(selectedReason);
  }

  private static String safe(String value) {
    return value == null ? "" : value.strip();
  }

  private static String normalizeHash(String value) {
    String normalized = safe(value);
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
