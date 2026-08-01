package com.wish.rd.rag.project.agent.model;

import java.util.List;

/** Expected runtime context loading policy frozen before provider dispatch. */
public record RuntimeContextPolicy(
    String protocol,
    String policyHash,
    RuntimeContextPolicyMode mode,
    List<RuntimeContextFileExpectation> expectedFiles
) {

  public RuntimeContextPolicy {
    protocol = protocol == null || protocol.isBlank()
        ? "rd-runtime-context-policy/v1"
        : protocol.strip();
    policyHash = normalizeHash(policyHash);
    mode = mode == null ? RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY : mode;
    expectedFiles = expectedFiles == null ? List.of() : List.copyOf(expectedFiles);
  }

  private static String normalizeHash(String value) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      return "";
    }
    return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
  }
}
