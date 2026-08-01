package com.wish.rd.rag.project.agent.model;

/** Expected repo-relative context file frozen by the host before runtime observation. */
public record RuntimeContextFileExpectation(String path, String contentHash) {

  public RuntimeContextFileExpectation {
    path = path == null ? "" : path.strip().replace('\\', '/');
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
