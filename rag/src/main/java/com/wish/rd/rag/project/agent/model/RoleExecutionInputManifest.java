package com.wish.rd.rag.project.agent.model;

import java.util.List;

/**
 * Immutable audit manifest describing every input source bound to one stage dispatch.
 *
 * <p>Phase 0 records the current implicit loading behavior without enforcing new policy.
 */
public record RoleExecutionInputManifest(
    int schemaVersion,
    String taskId,
    String stageRunId,
    String role,
    int attemptNo,
    RuntimeContextPolicyMode mode,
    RoleContractReference roleContract,
    TaskBaselineReference taskBaseline,
    List<RoleExecutionEvidenceEntry> evidence,
    List<HandoffManifestEntry> handoffs,
    RecoveryManifestEntry recovery,
    RuntimeContextPolicy runtimeContextPolicy,
    RoleExecutionPromptAudit prompt,
    MaterialsSummaryReference materialsSummary,
    ExecutionProfileReference executionProfile,
    RoleExecutionBudget budget,
    String semanticSignature,
    String contextPackageId,
    String promptArtifactId
) {

  public static final int CURRENT_SCHEMA_VERSION = 1;
  public static final String ARTIFACT_TYPE = "ROLE_EXECUTION_INPUT_MANIFEST";

  public RoleExecutionInputManifest {
    schemaVersion = schemaVersion <= 0 ? CURRENT_SCHEMA_VERSION : schemaVersion;
    taskId = requireText(taskId, "taskId");
    stageRunId = requireText(stageRunId, "stageRunId");
    role = requireText(role, "role").toUpperCase();
    attemptNo = Math.max(1, attemptNo);
    mode = mode == null ? RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY : mode;
    roleContract = roleContract == null ? new RoleContractReference("inline-v1", "") : roleContract;
    taskBaseline = taskBaseline == null ? new TaskBaselineReference("", "") : taskBaseline;
    evidence = evidence == null ? List.of() : List.copyOf(evidence);
    handoffs = handoffs == null ? List.of() : List.copyOf(handoffs);
    recovery = recovery == null ? new RecoveryManifestEntry("", "") : recovery;
    runtimeContextPolicy = runtimeContextPolicy == null
        ? new RuntimeContextPolicy("rd-runtime-context-policy/v1", "", RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY, List.of())
        : runtimeContextPolicy;
    prompt = prompt == null
        ? new RoleExecutionPromptAudit("", 0L, 0L, "chars/4-v1")
        : prompt;
    materialsSummary = materialsSummary == null
        ? new MaterialsSummaryReference("", 0)
        : materialsSummary;
    executionProfile = executionProfile == null
        ? new ExecutionProfileReference("", "")
        : executionProfile;
    budget = budget == null
        ? new RoleExecutionBudget("", 0L, 0L, 0L, "chars/4-v1")
        : budget;
    semanticSignature = semanticSignature == null ? "" : semanticSignature.strip();
    contextPackageId = contextPackageId == null ? "" : contextPackageId.strip();
    promptArtifactId = promptArtifactId == null ? "" : promptArtifactId.strip();
  }

  public String manifestHash() {
    return AgentManifestCanonicalJson.manifestHash(this);
  }

  public String canonicalJson() {
    return AgentManifestCanonicalJson.canonicalJson(this);
  }

  private static String requireText(String value, String fieldName) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return normalized;
  }
}
