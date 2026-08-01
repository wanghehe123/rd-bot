package com.wish.rd.exec.repair.pi;

import com.wish.rd.rag.project.agent.model.RuntimeContextFileDecision;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Host-side validator comparing frozen policy expectations to the post-reload runtime
 * context manifest (single source of truth after bridge reload).
 */
public final class RuntimeContextPreflightValidator {

  /** Optional identity bindings frozen at Host dispatch and required on the runtime manifest. */
  public record ExpectedIdentity(
      String taskId,
      String stageRunId,
      String role,
      int attemptNo,
      String executionProfileSnapshotId,
      String inputManifestHash,
      String contextPolicyHash
  ) {
    public ExpectedIdentity {
      taskId = normalize(taskId);
      stageRunId = normalize(stageRunId);
      role = normalize(role).toUpperCase(Locale.ROOT);
      attemptNo = Math.max(0, attemptNo);
      executionProfileSnapshotId = normalize(executionProfileSnapshotId);
      inputManifestHash = normalizeHash(inputManifestHash);
      contextPolicyHash = normalizeHash(contextPolicyHash);
    }

    private static String normalize(String value) {
      return value == null ? "" : value.strip();
    }

    private static String normalizeHash(String value) {
      String normalized = normalize(value);
      if (normalized.isBlank()) {
        return "";
      }
      return normalized.startsWith("sha256:") ? normalized : "sha256:" + normalized;
    }
  }

  public record ValidationResult(boolean accepted, List<String> violations) {
    public ValidationResult {
      violations = violations == null ? List.of() : List.copyOf(violations);
    }
  }

  public ValidationResult validate(RuntimeContextPolicy policy, RuntimeContextManifest manifest) {
    return validate(policy, manifest, null);
  }

  public ValidationResult validate(
      RuntimeContextPolicy policy,
      RuntimeContextManifest manifest,
      ExpectedIdentity expectedIdentity
  ) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(manifest, "manifest");

    List<String> violations = new ArrayList<>();
    if (!"ACCEPTED".equals(manifest.status())) {
      violations.add("manifest status must be ACCEPTED but was " + manifest.status());
    }
    if (manifest.mode() != policy.mode()) {
      violations.add("manifest mode mismatch: expected " + policy.mode() + " got " + manifest.mode());
    }
    if (!policy.policyHash().isBlank()
        && !policy.policyHash().equals(manifest.contextPolicyHash())) {
      violations.add("contextPolicyHash mismatch: expected "
          + policy.policyHash()
          + " got "
          + manifest.contextPolicyHash());
    }

    if (expectedIdentity != null) {
      requireEqual(violations, "taskId", expectedIdentity.taskId(), manifest.taskId());
      requireEqual(violations, "stageRunId", expectedIdentity.stageRunId(), manifest.stageRunId());
      requireEqual(violations, "role", expectedIdentity.role(), manifest.role());
      if (expectedIdentity.attemptNo() > 0 && expectedIdentity.attemptNo() != manifest.attemptNo()) {
        violations.add("attemptNo mismatch: expected "
            + expectedIdentity.attemptNo()
            + " got "
            + manifest.attemptNo());
      }
      if (!expectedIdentity.executionProfileSnapshotId().isBlank()) {
        requireEqual(
            violations,
            "executionProfileSnapshotId",
            expectedIdentity.executionProfileSnapshotId(),
            manifest.executionProfileSnapshotId()
        );
      }
      if (!expectedIdentity.inputManifestHash().isBlank()) {
        requireEqual(
            violations,
            "inputManifestHash",
            expectedIdentity.inputManifestHash(),
            manifest.inputManifestHash()
        );
      }
      if (!expectedIdentity.contextPolicyHash().isBlank()) {
        requireEqual(
            violations,
            "contextPolicyHash",
            expectedIdentity.contextPolicyHash(),
            manifest.contextPolicyHash()
        );
      }
    }

    List<RuntimeContextFileDecision> loaded = manifest.observedFiles().stream()
        .filter(file -> "LOADED".equals(file.trustDecision()))
        .toList();
    List<RuntimeContextFileDecision> rejected = manifest.observedFiles().stream()
        .filter(file -> !"LOADED".equals(file.trustDecision()))
        .toList();

    if (policy.mode() == RuntimeContextPolicyMode.ROOT_ONLY) {
      for (RuntimeContextFileDecision file : loaded) {
        if (file.path().contains("/")) {
          violations.add("nested file loaded under ROOT_ONLY: " + file.path());
        }
      }
    }

    for (RuntimeContextFileDecision file : rejected) {
      if (file.rejectReason().isBlank()) {
        violations.add("rejected file missing rejectReason: " + file.path());
      }
    }

    long loadedBytes = loaded.stream().mapToLong(RuntimeContextFileDecision::bytes).sum();
    if (manifest.totalFiles() != loaded.size()) {
      violations.add("totalFiles mismatch: expected " + loaded.size() + " got " + manifest.totalFiles());
    }
    if (manifest.totalBytes() != loadedBytes) {
      violations.add("totalBytes mismatch: expected " + loadedBytes + " got " + manifest.totalBytes());
    }

    List<RuntimeContextFileExpectation> expectedFiles = policy.expectedFiles();
    if (!expectedFiles.isEmpty()) {
      List<String> loadedPaths = loaded.stream().map(RuntimeContextFileDecision::path).toList();
      List<String> expectedPaths = expectedFiles.stream()
          .map(RuntimeContextFileExpectation::path)
          .toList();
      if (!loadedPaths.equals(expectedPaths)) {
        violations.add("loaded file order mismatch: expected "
            + String.join(",", expectedPaths)
            + " got "
            + String.join(",", loadedPaths));
      }
      for (RuntimeContextFileExpectation expected : expectedFiles) {
        RuntimeContextFileDecision observed = loaded.stream()
            .filter(file -> expected.path().equals(file.path()))
            .findFirst()
            .orElse(null);
        if (observed == null) {
          violations.add("expected file not loaded: " + expected.path());
          continue;
        }
        if (!expected.contentHash().isBlank()
            && !expected.contentHash().equals(observed.contentHash())) {
          violations.add("hash mismatch for " + expected.path());
        }
        if (observed.bytes() <= 0L) {
          violations.add("loaded file bytes must be > 0: " + expected.path());
        }
        if (observed.loadOrder() <= 0) {
          violations.add("loaded file loadOrder must be > 0: " + expected.path());
        }
      }
      for (RuntimeContextFileDecision observed : loaded) {
        boolean expected = expectedFiles.stream().anyMatch(file -> file.path().equals(observed.path()));
        if (!expected) {
          violations.add("undeclared file loaded: " + observed.path());
        }
      }
    }

    return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
  }

  private static void requireEqual(List<String> violations, String field, String expected, String actual) {
    if (expected == null || expected.isBlank()) {
      return;
    }
    String normalizedActual = actual == null ? "" : actual.strip();
    if (!expected.equals(normalizedActual)) {
      violations.add(field + " mismatch: expected " + expected + " got " + normalizedActual);
    }
  }
}
