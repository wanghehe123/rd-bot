package com.wish.rd.exec.repair.pi;

import com.wish.rd.rag.project.agent.model.RuntimeContextFileDecision;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeContextPreflightValidatorTest {

  private final RuntimeContextPreflightValidator validator = new RuntimeContextPreflightValidator();

  @Test
  void shouldAcceptMatchingRootOnlyManifest() {
    RuntimeContextPolicy policy = new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        RuntimeContextPolicyMode.ROOT_ONLY,
        List.of(
            new RuntimeContextFileExpectation("AGENTS.md", "sha256:111"),
            new RuntimeContextFileExpectation("CLAUDE.md", "sha256:222")
        )
    );
    RuntimeContextManifest manifest = manifest(
        "ACCEPTED",
        List.of(
            loaded("AGENTS.md", "sha256:111", 1, 12),
            loaded("CLAUDE.md", "sha256:222", 2, 12)
        )
    );

    RuntimeContextPreflightValidator.ValidationResult result = validator.validate(policy, manifest);

    assertTrue(result.accepted());
    assertTrue(result.violations().isEmpty());
  }

  @Test
  void shouldRejectHashMismatchAndNestedLoads() {
    RuntimeContextPolicy policy = new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        RuntimeContextPolicyMode.ROOT_ONLY,
        List.of(new RuntimeContextFileExpectation("AGENTS.md", "sha256:111"))
    );
    RuntimeContextManifest hashMismatch = manifest(
        "ACCEPTED",
        List.of(loaded("AGENTS.md", "sha256:999", 1, 12))
    );
    RuntimeContextManifest nested = manifest(
        "ACCEPTED",
        List.of(loaded("src/AGENTS.md", "sha256:111", 1, 12))
    );
    RuntimeContextManifest rejectedStatus = manifest(
        "REJECTED",
        List.of(loaded("AGENTS.md", "sha256:111", 1, 12))
    );

    assertFalse(validator.validate(policy, hashMismatch).accepted());
    assertTrue(validator.validate(policy, hashMismatch).violations().stream()
        .anyMatch(violation -> violation.contains("hash mismatch")));
    assertFalse(validator.validate(policy, nested).accepted());
    assertTrue(validator.validate(policy, nested).violations().stream()
        .anyMatch(violation -> violation.contains("nested file")));
    assertFalse(validator.validate(policy, rejectedStatus).accepted());
    assertEquals(
        "manifest status must be ACCEPTED but was REJECTED",
        validator.validate(policy, rejectedStatus).violations().getFirst()
    );
  }

  @Test
  void shouldRejectIdentityAndRejectReasonGaps() {
    RuntimeContextPolicy policy = new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        RuntimeContextPolicyMode.ROOT_ONLY,
        List.of(new RuntimeContextFileExpectation("AGENTS.md", "sha256:111"))
    );
    RuntimeContextManifest manifest = new RuntimeContextManifest(
        1,
        RuntimeContextManifest.PROTOCOL,
        "task-other",
        "stage-1",
        "CODING_AGENT",
        2,
        RuntimeContextPolicyMode.ROOT_ONLY,
        "PI",
        "anthropic",
        "claude",
        "snapshot-other",
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "2026-08-01T00:00:00Z",
        List.of(
            loaded("AGENTS.md", "sha256:111", 1, 12),
            new RuntimeContextFileDecision(
                "src/AGENTS.md", "", 0, 0, "REPO", "REJECTED", "")
        ),
        1,
        12,
        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        "ACCEPTED"
    );
    RuntimeContextPreflightValidator.ExpectedIdentity identity =
        new RuntimeContextPreflightValidator.ExpectedIdentity(
            "task-1",
            "stage-1",
            "CODING_AGENT",
            1,
            "snapshot-1",
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        );

    RuntimeContextPreflightValidator.ValidationResult result =
        validator.validate(policy, manifest, identity);

    assertFalse(result.accepted());
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("taskId mismatch")));
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("attemptNo mismatch")));
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("executionProfileSnapshotId mismatch")));
    assertTrue(result.violations().stream().anyMatch(v -> v.contains("missing rejectReason")));
  }

  private static RuntimeContextManifest manifest(String status, List<RuntimeContextFileDecision> observed) {
    long bytes = observed.stream()
        .filter(file -> "LOADED".equals(file.trustDecision()))
        .mapToLong(RuntimeContextFileDecision::bytes)
        .sum();
    int loadedCount = (int) observed.stream()
        .filter(file -> "LOADED".equals(file.trustDecision()))
        .count();
    return new RuntimeContextManifest(
        1,
        RuntimeContextManifest.PROTOCOL,
        "task-1",
        "stage-1",
        "CODING_AGENT",
        1,
        RuntimeContextPolicyMode.ROOT_ONLY,
        "PI",
        "anthropic",
        "claude",
        "snapshot-1",
        "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "2026-08-01T00:00:00Z",
        observed,
        loadedCount,
        bytes,
        "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
        status
    );
  }

  private static RuntimeContextFileDecision loaded(String path, String hash, int order, long bytes) {
    return new RuntimeContextFileDecision(path, hash, bytes, order, "REPO", "LOADED", "");
  }
}
