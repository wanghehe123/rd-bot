package com.wish.rd.rag.project.agent.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleExecutionInputManifestTest {

  @Test
  void shouldProduceStableCanonicalHashRegardlessOfMapInsertionOrder() {
    RoleExecutionInputManifest left = sampleManifest("task-a", "stage-a");
    RoleExecutionInputManifest right = sampleManifest("task-a", "stage-a");

    assertEquals(left.manifestHash(), right.manifestHash());
    assertEquals(left.canonicalJson(), right.canonicalJson());
    assertNotEquals(
            left.manifestHash(),
            sampleManifest("task-b", "stage-a").manifestHash()
    );
  }

  @Test
  void shouldRejectBlankIdentityFields() {
    assertThrows(IllegalArgumentException.class, () -> sampleManifest("", "stage-a"));
    assertThrows(IllegalArgumentException.class, () -> sampleManifest("task-a", ""));
    assertThrows(IllegalArgumentException.class, () -> new RoleExecutionInputManifest(
            1,
            "task-a",
            "stage-a",
            "",
            1,
            RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
            new RoleContractReference("inline-v1", "sha256:abc"),
            new TaskBaselineReference("pkg-1", "sha256:def"),
            List.of(),
            List.of(),
            new RecoveryManifestEntry("", ""),
            new RuntimeContextPolicy(
                    "rd-runtime-context-policy/v1",
                    "sha256:policy",
                    RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
                    List.of()
            ),
            new RoleExecutionPromptAudit("sha256:prompt", 120L, 30L, "chars/4-v1"),
            new MaterialsSummaryReference("sha256:materials", 2),
            new ExecutionProfileReference("snap-1", ""),
            new RoleExecutionBudget("deepseek-v4-flash", 128_000L, 8_192L, 30L, "chars/4-v1"),
            "semantic-v1",
            "pkg-1",
            "prompt-1",
            List.of()
    ));
  }

  @Test
  void shouldNormalizeBudgetUnavailableSentinelsAndAttemptFloor() {
    RoleExecutionInputManifest manifest = new RoleExecutionInputManifest(
            0,
            "task-a",
            "stage-a",
            "coding_agent",
            0,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            null
    );

    assertEquals(1, manifest.schemaVersion());
    assertEquals("CODING_AGENT", manifest.role());
    assertEquals(1, manifest.attemptNo());
    assertEquals(RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY, manifest.mode());
    RoleExecutionBudget budget = manifest.budget();
    assertEquals(RoleExecutionBudget.UNAVAILABLE_MODEL, budget.model());
    assertEquals(RoleExecutionBudget.UNAVAILABLE_TOKENS, budget.maxContextTokens());
    assertEquals(RoleExecutionBudget.UNAVAILABLE_TOKENS, budget.reservedOutputTokens());
    assertEquals(0L, budget.estimatedInputTokens());
    assertEquals("chars/4-v1", budget.estimatorVersion());
    assertFalse(budget.modelAvailable());
    assertFalse(budget.contextBudgetAvailable());
    assertFalse(budget.reservedOutputAvailable());

    RoleExecutionBudget explicitBudget = new RoleExecutionBudget(null, -5L, -1L, -3L, null);
    assertEquals("", explicitBudget.model());
    assertEquals(RoleExecutionBudget.UNAVAILABLE_TOKENS, explicitBudget.maxContextTokens());
    assertEquals(RoleExecutionBudget.UNAVAILABLE_TOKENS, explicitBudget.reservedOutputTokens());
    assertEquals(0L, explicitBudget.estimatedInputTokens());
  }

  private static RoleExecutionInputManifest sampleManifest(String taskId, String stageRunId) {
    return new RoleExecutionInputManifest(
            1,
            taskId,
            stageRunId,
            "CODING_AGENT",
            1,
            RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
            new RoleContractReference("inline-v1", "sha256:abc"),
            new TaskBaselineReference("pkg-1", "sha256:def"),
            List.of(new RoleExecutionEvidenceEntry(
                    "ev-1", "TASK_INPUT", "DECLARED", "sha256:111", "sha256:222", false, "selected"
            )),
            List.of(),
            new RecoveryManifestEntry("", ""),
            new RuntimeContextPolicy(
                    "rd-runtime-context-policy/v1",
                    "sha256:policy",
                    RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
                    List.of()
            ),
            new RoleExecutionPromptAudit("sha256:prompt", 120L, 30L, "chars/4-v1"),
            new MaterialsSummaryReference("sha256:materials", 2),
            new ExecutionProfileReference("snap-1", ""),
            new RoleExecutionBudget("deepseek-v4-flash", 128_000L, 8_192L, 30L, "chars/4-v1"),
            "semantic-v1",
            "pkg-1",
            "prompt-1",
            List.of("input-manifest-1", "runtime-manifest-1")
    );
  }
}
