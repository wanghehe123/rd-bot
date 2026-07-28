package com.wish.rd.bootstrap.executor.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.requirement.RequirementExecutionProfileResolverPort;
import com.wish.rd.engine.requirement.model.RequirementExecutionProfileResolution;
import com.wish.rd.rag.project.agent.AgentExecutionProfileService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotService;
import com.wish.rd.rag.project.agent.AgentExecutionProfileSnapshotStore;
import com.wish.rd.rag.project.agent.AgentToolPolicyService;
import com.wish.rd.rag.project.agent.ModelProviderProfileService;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfile;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import com.wish.rd.rag.project.agent.model.AgentToolPolicy;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;
import com.wish.rd.rag.project.agent.model.ModelProviderProfile;
import com.wish.rd.rag.project.agent.model.ModelProviderProtocol;
import com.wish.rd.rag.runtime.model.RdRequirementTask;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Resolves one requirement-delivery profile and freezes it before RUNNING. */
public final class EngineRequirementExecutionProfileResolver
        implements RequirementExecutionProfileResolverPort {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AgentExecutionProfileService profileService;
    private final AgentExecutionProfileSnapshotService snapshotService;
    private final AgentExecutionProfileSnapshotStore snapshotStore;
    private final boolean openAiChatEnabled;
    private final ModelProviderProfileService providerProfileService;
    private final AgentToolPolicyService toolPolicyService;

    public EngineRequirementExecutionProfileResolver(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotService snapshotService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            boolean openAiChatEnabled
    ) {
        this(
                profileService,
                snapshotService,
                snapshotStore,
                openAiChatEnabled,
                null,
                null
        );
    }

    public EngineRequirementExecutionProfileResolver(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotService snapshotService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            boolean openAiChatEnabled,
            ModelProviderProfileService providerProfileService,
            AgentToolPolicyService toolPolicyService
    ) {
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.openAiChatEnabled = openAiChatEnabled;
        this.providerProfileService = providerProfileService;
        this.toolPolicyService = toolPolicyService;
    }

    @Override
    public RequirementExecutionProfileResolution resolve(
            RdRequirementTask task,
            AgentRole role,
            String stageRunId,
            int attemptNo
    ) {
        if (task == null || role == null) {
            throw new IllegalArgumentException("task and role must not be null");
        }
        String safeStageRunId = requireText(stageRunId, "stageRunId");
        AgentExecutionProfileSnapshot existing = snapshotStore.findByStageRunId(safeStageRunId)
                .orElse(null);
        if (existing != null) {
            verifyExisting(existing, task, role, attemptNo);
            return new RequirementExecutionProfileResolution(existing.snapshotId());
        }

        AgentExecutionProfile profile = profileService.resolve(task.projectId(), task.taskId(), role.name())
                .orElse(null);
        AgentRuntimeType runtimeType = profile == null
                ? compatibilityRuntime(role)
                : profile.runtimeType();
        String snapshotJson = snapshotJson(task, role, safeStageRunId, attemptNo, runtimeType, profile);
        AgentExecutionProfileSnapshot requested = new AgentExecutionProfileSnapshot(
                snapshotId(safeStageRunId),
                safeStageRunId,
                task.taskId(),
                role.name(),
                attemptNo,
                runtimeType,
                snapshotJson,
                AgentExecutionProfileSnapshot.sha256(snapshotJson),
                System.currentTimeMillis()
        );
        AgentExecutionProfileSnapshot stored = snapshotService.resolveOrSave(requested);
        return new RequirementExecutionProfileResolution(stored.snapshotId());
    }

    private void verifyExisting(
            AgentExecutionProfileSnapshot snapshot,
            RdRequirementTask task,
            AgentRole role,
            int attemptNo
    ) {
        if (!snapshot.hasValidIntegrityHash()) {
            throw new IllegalStateException(
                    "AGENT_RUNTIME_SNAPSHOT_CORRUPT: " + snapshot.snapshotId()
            );
        }
        if (!snapshot.taskId().equals(task.taskId())
                || !snapshot.role().equals(role.name())
                || snapshot.attemptNo() != attemptNo) {
            throw new IllegalStateException(
                    "AGENT_RUNTIME_SNAPSHOT_CORRUPT: snapshot identity mismatch: " + snapshot.snapshotId()
            );
        }
    }

    private String snapshotJson(
            RdRequirementTask task,
            AgentRole role,
            String stageRunId,
            int attemptNo,
            AgentRuntimeType runtimeType,
            AgentExecutionProfile profile
    ) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("snapshotVersion", 1);
        value.put("stageRunId", stageRunId);
        value.put("taskId", task.taskId());
        value.put("role", role.name());
        value.put("attemptNo", attemptNo);
        value.put("runtimeType", runtimeType.name());
        value.put("profileId", profile == null ? "compatibility-default" : profile.profileId());
        value.put("profileVersion", profile == null ? 0L : profile.version());
        value.put("providerProfileId", profile == null ? "legacy" : profile.providerProfileId());
        value.put("modelOverride", profile == null ? "" : profile.modelOverride());
        value.put("extensionSetId", profile == null ? "" : profile.extensionSetId());
        value.put("extensionSetVersion", profile == null ? 0L : profile.extensionSetVersion());
        AgentToolPolicy toolPolicy = resolveToolPolicy(runtimeType, profile);
        value.put("toolPolicyId", toolPolicy.policyId());
        value.put("toolPolicyVersion", toolPolicy.version());
        value.put("toolPolicy", toolPolicyJson(toolPolicy));
        ModelProviderProfile provider = resolveProvider(runtimeType, profile);
        value.put("providerProtocol", provider == null ? "" : provider.protocol().name());
        value.put("providerBaseUrl", provider == null ? "" : provider.baseUrl());
        value.put("providerModelId", provider == null ? "" : provider.modelId());
        value.put("providerProfileVersion", provider == null ? 0L : provider.version());
        value.put("providerAuthHeader", provider != null && provider.authHeader());
        value.put("credentialEnvironmentVariable", provider == null ? "" : provider.credentialEnvironmentVariable());
        value.put("sessionPolicy", "ARCHIVE_NO_RESUME");
        value.put("resolvedFrom", profile == null ? "COMPATIBILITY_DEFAULT" : "REGISTERED_PROFILE");
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE: serialize snapshot", exception);
        }
    }

    private AgentToolPolicy resolveToolPolicy(AgentRuntimeType runtimeType, AgentExecutionProfile profile) {
        if (profile == null) {
            return AgentToolPolicy.defaultCodingPolicy();
        }
        if (toolPolicyService == null) {
            return new AgentToolPolicy(
                    profile.toolPolicyId(),
                    profile.toolPolicyVersion(),
                    AgentToolPolicy.defaultCodingPolicy().hostAllow(),
                    AgentToolPolicy.defaultCodingPolicy().allow(),
                    AgentToolPolicy.defaultCodingPolicy().deny(),
                    true
            );
        }
        return toolPolicyService.find(profile.toolPolicyId(), profile.toolPolicyVersion())
                .filter(AgentToolPolicy::enabled)
                .orElseThrow(() -> new IllegalStateException(
                        "AGENT_RUNTIME_PROFILE_INVALID: tool policy is unavailable: " + profile.toolPolicyId()
                                + "@" + profile.toolPolicyVersion()
                ));
    }

    private ModelProviderProfile resolveProvider(AgentRuntimeType runtimeType, AgentExecutionProfile profile) {
        if (profile == null || runtimeType != AgentRuntimeType.PI) {
            return null;
        }
        if (providerProfileService == null) {
            return null;
        }
        return providerProfileService.find(profile.providerProfileId())
                .filter(ModelProviderProfile::enabled)
                .orElseThrow(() -> new IllegalStateException(
                        "AGENT_RUNTIME_PROFILE_INVALID: provider profile is unavailable: "
                                + profile.providerProfileId()
                ));
    }

    private static Map<String, Object> toolPolicyJson(AgentToolPolicy policy) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hostAllow", policy.hostAllow().stream().sorted().toList());
        value.put("allow", policy.allow().stream().sorted().toList());
        value.put("deny", policy.deny().stream().sorted().toList());
        value.put("effectiveAllow", policy.effectiveAllow());
        value.put("enabled", policy.enabled());
        return value;
    }

    private AgentRuntimeType compatibilityRuntime(AgentRole role) {
        if (role == AgentRole.REQUIREMENT_REVIEWER || role == AgentRole.SOLUTION_ARCHITECT) {
            return openAiChatEnabled ? AgentRuntimeType.MODEL_ONLY : AgentRuntimeType.CLAUDE_CODE;
        }
        return AgentRuntimeType.CLAUDE_CODE;
    }

    private static String snapshotId(String stageRunId) {
        return "agent-profile-" + stageRunId;
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
