package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextPolicyTest {

  @Test
  void shouldNormalizePolicyHashAndDefaultMode() {
    RuntimeContextPolicy policy = new RuntimeContextPolicy(
        null,
        "abc123",
        null,
        null
    );

    assertEquals("rd-runtime-context-policy/v1", policy.protocol());
    assertEquals("sha256:abc123", policy.policyHash());
    assertEquals(RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY, policy.mode());
    assertTrue(policy.expectedFiles().isEmpty());
  }

  @Test
  void shouldPreserveExplicitModeAndExpectedFiles() {
    RuntimeContextPolicy policy = new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        "sha256:deadbeef",
        RuntimeContextPolicyMode.ROOT_ONLY,
        List.of(new RuntimeContextFileExpectation("AGENTS.md", "sha256:111"))
    );

    assertEquals(RuntimeContextPolicyMode.ROOT_ONLY, policy.mode());
    assertEquals(1, policy.expectedFiles().size());
    assertEquals("AGENTS.md", policy.expectedFiles().getFirst().path());
    assertEquals("sha256:111", policy.expectedFiles().getFirst().contentHash());
  }
}
