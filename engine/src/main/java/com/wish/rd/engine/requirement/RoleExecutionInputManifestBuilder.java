package com.wish.rd.engine.requirement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.context.model.RoleContextEvidence;
import com.wish.rd.rag.context.model.RoleContextPackage;
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
      String recoverySourceStageRunId,
      RoleContextVersionManager roleContextVersionManager
  ) {
    String promptHash = AgentManifestCanonicalJson.contentHash(rolePrompt == null ? "" : rolePrompt);
    long promptBytes = (rolePrompt == null ? "" : rolePrompt).getBytes(StandardCharsets.UTF_8).length;
    long estimatedTokens = Math.max(1L, (promptBytes + 3L) / 4L);
    String semanticSignature = roleContextVersionManager == null
        ? ""
        : roleContextVersionManager.semanticSignatureOf(roleContext);
    RuntimeContextPolicy runtimeContextPolicy = new RuntimeContextPolicy(
        "rd-runtime-context-policy/v1",
        AgentManifestCanonicalJson.manifestHash(Map.of(
                "mode", RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY.name(),
                "expectedFiles", List.of()
        )),
        RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
        List.of()
    );
    return new RoleExecutionInputManifest(
        RoleExecutionInputManifest.CURRENT_SCHEMA_VERSION,
        stage.taskId(),
        stage.stageRunId(),
        role.name(),
        stage.attemptNo(),
        RuntimeContextPolicyMode.LEGACY_OBSERVE_ONLY,
        new RoleContractReference("inline-v1", AgentManifestCanonicalJson.contentHash(roleContractText)),
        new TaskBaselineReference(
                roleContext.packageId(),
                AgentManifestCanonicalJson.contentHash(taskBaselineFingerprint(task, roleContext))
        ),
        evidenceEntries(roleContext),
        handoffEntries(upstreamResultJson),
        new RecoveryManifestEntry(
                recoverySourceStageRunId == null ? "" : recoverySourceStageRunId,
                recoveryContent == null || recoveryContent.isBlank()
                        ? ""
                        : AgentManifestCanonicalJson.contentHash(recoveryContent)
        ),
        runtimeContextPolicy,
        new RoleExecutionPromptAudit(promptHash, promptBytes, estimatedTokens, ESTIMATOR_VERSION),
        materialsSummary(materials),
        executionProfileReference(executionProfileResolution),
        new RoleExecutionBudget("", 0L, 0L, estimatedTokens, ESTIMATOR_VERSION),
        semanticSignature,
        roleContext.packageId(),
        stage.promptArtifactId()
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

  private static List<HandoffManifestEntry> handoffEntries(String upstreamResultJson) {
    List<HandoffManifestEntry> entries = new ArrayList<>();
    try {
      JsonNode root = OBJECT_MAPPER.readTree(upstreamResultJson == null ? "[]" : upstreamResultJson);
      if (!root.isArray()) {
        return List.of();
      }
      for (JsonNode stage : root) {
        JsonNode handoff = stage.path("handoff");
        if (!handoff.isObject()) {
          continue;
        }
        String artifactUri = handoff.path("artifactUri").asText("");
        String sha256 = handoff.path("sha256").asText("");
        if (artifactUri.isBlank() || sha256.isBlank()) {
          continue;
        }
        entries.add(new HandoffManifestEntry(artifactUri, sha256));
      }
    } catch (JsonProcessingException ignored) {
      return List.of();
    }
    return List.copyOf(entries);
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
    if (executionProfileResolution == null) {
      return new ExecutionProfileReference("", "");
    }
    return new ExecutionProfileReference(
            executionProfileResolution.snapshotId(),
            ""
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
}
