package com.wish.rd.exec.repair.pi;

import com.wish.rd.rag.project.agent.model.RuntimeContextFileDecision;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextManifest;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Host-side validator comparing frozen policy expectations to observed preflight manifest. */
public final class RuntimeContextPreflightValidator {

  public record ValidationResult(boolean accepted, List<String> violations) {
    public ValidationResult {
      violations = violations == null ? List.of() : List.copyOf(violations);
    }
  }

  public ValidationResult validate(RuntimeContextPolicy policy, RuntimeContextManifest manifest) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(manifest, "manifest");

    List<String> violations = new ArrayList<>();
    if (!"ACCEPTED".equals(manifest.status())) {
      violations.add("manifest status must be ACCEPTED but was " + manifest.status());
    }

    List<RuntimeContextFileDecision> loaded = manifest.observedFiles().stream()
        .filter(file -> "LOADED".equals(file.trustDecision()))
        .toList();

    if (policy.mode() == RuntimeContextPolicyMode.ROOT_ONLY) {
      for (RuntimeContextFileDecision file : loaded) {
        if (file.path().contains("/")) {
          violations.add("nested file loaded under ROOT_ONLY: " + file.path());
        }
      }
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
      }
    }

    return new ValidationResult(violations.isEmpty(), List.copyOf(violations));
  }
}
