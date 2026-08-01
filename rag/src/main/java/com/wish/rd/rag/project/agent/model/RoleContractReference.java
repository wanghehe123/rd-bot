package com.wish.rd.rag.project.agent.model;

/** Immutable reference to the role contract text rendered for one stage attempt. */
public record RoleContractReference(String version, String contentHash) {

  public RoleContractReference {
    version = normalize(version, "inline-v1");
    contentHash = normalizeHash(contentHash);
  }

  private static String normalize(String value, String fallback) {
    String normalized = value == null ? "" : value.strip();
    return normalized.isBlank() ? fallback : normalized;
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
