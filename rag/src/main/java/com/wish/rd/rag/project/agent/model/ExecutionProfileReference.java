package com.wish.rd.rag.project.agent.model;

/** Frozen execution profile snapshot reference for one stage attempt. */
public record ExecutionProfileReference(String snapshotId, String snapshotHash) {

  public ExecutionProfileReference {
    snapshotId = snapshotId == null ? "" : snapshotId.strip();
    snapshotHash = normalizeHash(snapshotHash);
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    String lower = normalized.toLowerCase();
    if (lower.startsWith("sha256:")) {
      return normalized;
    }
    return "sha256:" + normalized;
  }
}
