package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentManifestCanonicalJson;
import com.wish.rd.rag.project.agent.model.ExecutionProfileReference;
import com.wish.rd.rag.project.agent.model.HandoffManifestEntry;
import com.wish.rd.rag.project.agent.model.MaterialsSummaryReference;
import com.wish.rd.rag.project.agent.model.RecoveryManifestEntry;
import com.wish.rd.rag.project.agent.model.RoleContractReference;
import com.wish.rd.rag.project.agent.model.RoleExecutionBudget;
import com.wish.rd.rag.project.agent.model.RoleExecutionEvidenceEntry;
import com.wish.rd.rag.project.agent.model.RoleExecutionInputManifest;
import com.wish.rd.rag.project.agent.model.RoleExecutionPromptAudit;
import com.wish.rd.rag.project.agent.model.RuntimeContextFileExpectation;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicy;
import com.wish.rd.rag.project.agent.model.RuntimeContextPolicyMode;
import com.wish.rd.rag.project.agent.model.TaskBaselineReference;
import com.wish.rd.rag.runtime.model.RdRequirementTask;
import com.wish.rd.rag.runtime.model.TaskMaterial;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds audit-only {@link RoleExecutionInputManifest} snapshots before stage dispatch. */
final class RoleExecutionInputManifestBuilder {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
  private static final String ESTIMATOR_VERSION = "chars/4-v1";

  private RoleExecutionInputManifestBuilder() {
  }

  static RoleExecutionInputManifest build(
      AgentStageRun stage,
      AgentRole role,
      RdRequirementTask task,
      List<TaskMaterial> materials,
      RoleContextPackage roleContext,
      String rolePrompt,
      String roleContractText,
      String upstreamResultJson,
      RequirementExecutionProfileResolution executionProfileResolution,
      String recoveryContent,
      TaskRetryCheckpoint recoveryCheckpoint,
      List<String> expectedArtifactIds,
      RoleContextVersionManager roleContextVersionManager
  ) {
    String promptHash = AgentManifestCanonicalJson.contentHash(rolePrompt == null ? "" : rolePrompt);
    long promptBytes = (rolePrompt == null ? "" : rolePrompt).getBytes(StandardCharsets.UTF_8).length;
    long estimatedTokens = Math.max(1L, (promptBytes + 3L) / 4L);
    String semanticSignature = roleContextVersionManager == null
        ? ""
        : roleContextVersionManager.semanticSignatureOf(roleContext);
    RuntimeContextPolicyMode contextMode = resolveContextPolicyMode(executionProfileResolution);
    RuntimeContextPolicy runtimeContextPolicy = buildRuntimeContextPolicy(contextMode);
    return new RoleExecutionInputManifest(
        RoleExecutionInputManifest.CURRENT_SCHEMA_VERSION,
        stage.taskId(),
        stage.stageRunId(),
        role.name(),
        stage.attemptNo(),
        contextMode,
        new RoleContractReference("inline-v1", AgentManifestCanonicalJson.contentHash(roleContractText)),
        new TaskBaselineReference(
                roleContext.packageId(),
                AgentManifestCanonicalJson.contentHash(taskBaselineFingerprint(task, roleContext))
        ),
        evidenceEntries(roleContext),
        handoffEntries(upstreamResultJson, role),
        recoveryEntry(recoveryContent, recoveryCheckpoint),
        runtimeContextPolicy,
        new RoleExecutionPromptAudit(promptHash, promptBytes, estimatedTokens, ESTIMATOR_VERSION),
        materialsSummary(materials),
        executionProfileReference(executionProfileResolution),
        executionBudget(executionProfileResolution, estimatedTokens),
        semanticSignature,
        roleContext.packageId(),
        stage.promptArtifactId(),
        expectedArtifactIds == null ? List.of() : List.copyOf(expectedArtifactIds)
    );
  }

  private static RuntimeContextPolicyMode resolveContextPolicyMode(
      RequirementExecutionProfileResolution executionProfileResolution
  ) {
    if (executionProfileResolution == null || executionProfileResolution.contextPolicyMode().isBlank()) {
      return RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY;
    }
    try {
      return RuntimeContextPolicyMode.valueOf(executionProfileResolution.contextPolicyMode().strip());
    } catch (IllegalArgumentException ignored) {
      return RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY;
    }
  }

  private static RuntimeContextPolicy buildRuntimeContextPolicy(RuntimeContextPolicyMode mode) {
    RuntimeContextPolicyMode resolved = mode == null
        ? RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY
        : mode;
    List<RuntimeContextFileExpectation> expectedFiles = resolved == RuntimeContextPolicyMode.ROOT_ONLY
        ? List.of(
            new RuntimeContextFileExpectation("AGENTS.md", ""),
            new RuntimeContextFileExpectation("CLAUDE.md", "")
        )
        : List.of();
    String policyHash = AgentManifestCanonicalJson.manifestHash(Map.of(
        "mode", resolved.name(),
        "expectedFiles", expectedFiles.stream()
            .map(file -> Map.of("path", file.path(), "contentHash", file.contentHash()))
            .toList()
    ));
    return new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        policyHash,
        resolved,
        expectedFiles
    );
  }

  private static List<RoleExecutionEvidenceEntry> evidenceEntries(RoleContextPackage roleContext) {
    List<RoleExecutionEvidenceEntry> entries = new ArrayList<>();
    for (RoleContextEvidence evidence : roleContext.evidence()) {
      entries.add(new RoleExecutionEvidenceEntry(
              evidence.evidenceId(),
              evidence.sourceType(),
              "DECLARED",
              evidence.contentHash(),
              AgentManifestCanonicalJson.contentHash(evidence.summary()),
              false,
              evidence.selectionReason()
      ));
    }
    return List.copyOf(entries);
  }

  private static List<HandoffManifestEntry> handoffEntries(String upstreamResultJson, AgentRole currentRole) {
    List<HandoffManifestEntry> entries = new ArrayList<>();
    String targetRole = currentRole == null ? "" : currentRole.name();
    try {
      JsonNode root = OBJECT_MAPPER.readTree(upstreamResultJson == null ? "{}" : upstreamResultJson);
      JsonNode stages = root.path("stages");
      if (!stages.isArray()) {
        return List.of();
      }
      for (JsonNode stage : stages) {
        JsonNode handoff = stage.path("handoff");
        if (!handoff.isObject()) {
          handoff = stage.path("roleHandoff");
        }
        if (!handoff.isObject()) {
          continue;
        }
        String handoffTarget = handoff.path("targetRole").asText("").strip().toUpperCase(Locale.ROOT);
        if (!handoffTarget.isBlank() && !handoffTarget.equals(targetRole)) {
          continue;
        }
        String artifactUri = handoff.path("artifactUri").asText("");
        String sha256 = handoff.path("sha256").asText("");
        if (artifactUri.isBlank() || sha256.isBlank()) {
          continue;
        }
        String sourceRole = firstNonBlank(
                handoff.path("sourceRole").asText(""),
                stage.path("role").asText("")
        );
        entries.add(new HandoffManifestEntry(
                handoff.path("artifactId").asText(""),
                artifactUri,
                sha256,
                sourceRole,
                handoffTarget.isBlank() ? targetRole : handoffTarget
        ));
      }
    } catch (JsonProcessingException ignored) {
      return List.of();
    }
    return List.copyOf(entries);
  }

  private static RecoveryManifestEntry recoveryEntry(
      String recoveryContent,
      TaskRetryCheckpoint recoveryCheckpoint
  ) {
    String sourceStageRunId = recoveryCheckpoint == null ? "" : recoveryCheckpoint.failedStageRunId();
    int sourceAttemptNo = recoveryCheckpoint == null
        ? RecoveryManifestEntry.UNKNOWN_ATTEMPT
        : recoveryCheckpoint.attemptNo();
    String reason = recoveryCheckpoint == null ? "" : recoveryCheckpoint.reason();
    if (reason.isBlank() && recoveryCheckpoint != null) {
      reason = recoveryCheckpoint.errorMessage();
    }
    String contentHash = recoveryContent == null || recoveryContent.isBlank()
        ? ""
        : AgentManifestCanonicalJson.contentHash(recoveryContent);
    return new RecoveryManifestEntry(sourceStageRunId, contentHash, sourceAttemptNo, reason);
  }

  private static MaterialsSummaryReference materialsSummary(List<TaskMaterial> materials) {
    if (materials == null || materials.isEmpty()) {
      return new MaterialsSummaryReference("", 0);
    }
    StringBuilder fingerprint = new StringBuilder();
    for (TaskMaterial material : materials) {
      fingerprint.append(material.materialId()).append('\u001f')
              .append(material.contentHash()).append('\u001f')
              .append(material.sourceUri()).append('\u001e');
    }
    return new MaterialsSummaryReference(
            AgentManifestCanonicalJson.contentHash(fingerprint.toString()),
            materials.size()
    );
  }

  private static ExecutionProfileReference executionProfileReference(
      RequirementExecutionProfileResolution executionProfileResolution
  ) {
    if (executionProfileResolution == null || !executionProfileResolution.resolved()) {
      return new ExecutionProfileReference("", "");
    }
    String snapshotHash = executionProfileResolution.snapshotJson().isBlank()
        ? ""
        : AgentExecutionProfileSnapshot.sha256(executionProfileResolution.snapshotJson());
    return new ExecutionProfileReference(
            executionProfileResolution.snapshotId(),
            snapshotHash
    );
  }

  private static RoleExecutionBudget executionBudget(
      RequirementExecutionProfileResolution executionProfileResolution,
      long estimatedInputTokens
  ) {
    if (executionProfileResolution == null || !executionProfileResolution.resolved()) {
      return unavailableBudget(estimatedInputTokens);
    }
    try {
      JsonNode root = OBJECT_MAPPER.readTree(executionProfileResolution.snapshotJson());
      String model = firstNonBlank(
              root.path("modelOverride").asText(""),
              root.path("providerModelId").asText("")
      );
      if (model.isBlank()) {
        model = RoleExecutionBudget.UNAVAILABLE_MODEL;
      }
      long maxContextTokens = root.path("maxContextTokens").asLong(RoleExecutionBudget.UNAVAILABLE_TOKENS);
      long reservedOutputTokens = root.path("reservedOutputTokens").asLong(RoleExecutionBudget.UNAVAILABLE_TOKENS);
      if (maxContextTokens <= 0L && maxContextTokens != RoleExecutionBudget.UNAVAILABLE_TOKENS) {
        maxContextTokens = RoleExecutionBudget.UNAVAILABLE_TOKENS;
      }
      if (reservedOutputTokens <= 0L && reservedOutputTokens != RoleExecutionBudget.UNAVAILABLE_TOKENS) {
        reservedOutputTokens = RoleExecutionBudget.UNAVAILABLE_TOKENS;
      }
      return new RoleExecutionBudget(model, maxContextTokens, reservedOutputTokens, estimatedInputTokens, ESTIMATOR_VERSION);
    } catch (JsonProcessingException exception) {
      return unavailableBudget(estimatedInputTokens);
    }
  }

  private static RoleExecutionBudget unavailableBudget(long estimatedInputTokens) {
    return new RoleExecutionBudget(
            RoleExecutionBudget.UNAVAILABLE_MODEL,
            RoleExecutionBudget.UNAVAILABLE_TOKENS,
            RoleExecutionBudget.UNAVAILABLE_TOKENS,
            estimatedInputTokens,
            ESTIMATOR_VERSION
    );
  }

  private static String taskBaselineFingerprint(RdRequirementTask task, RoleContextPackage roleContext) {
    Map<String, Object> baseline = new LinkedHashMap<>();
    baseline.put("taskId", task.taskId());
    baseline.put("title", task.title());
    baseline.put("expectedResult", task.expectedResult());
    baseline.put("acceptanceCriteria", roleContext.acceptanceCriteria());
    baseline.put("riskHints", roleContext.riskHints());
    baseline.put("packageVersion", roleContext.packageVersion());
    try {
      return OBJECT_MAPPER.writeValueAsString(baseline);
    } catch (JsonProcessingException exception) {
      return task.taskId() + "|" + roleContext.packageId();
    }
  }

  private static String firstNonBlank(String first, String second) {
    String safeFirst = first == null ? "" : first.strip();
    if (!safeFirst.isBlank()) {
      return safeFirst;
    }
    return second == null ? "" : second.strip();
  }
}
