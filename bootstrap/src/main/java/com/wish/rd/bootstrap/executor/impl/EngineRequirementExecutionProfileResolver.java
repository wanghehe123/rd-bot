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
import java.util.List;
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
    private final String contextProtocolVersion;
    private final String contextPolicyMode;
    private final boolean dynamicStateEnabled;
    private final int maxInjectedStateBytes;

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
                null,
                "LEGACY_ENVIRONMENT_NOTES",
                false,
                8192
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
        this(
                profileService,
                snapshotService,
                snapshotStore,
                openAiChatEnabled,
                providerProfileService,
                toolPolicyService,
                "LEGACY_ENVIRONMENT_NOTES",
                "LEGACY_OBSERVE_ONLY",
                false,
                8192
        );
    }

    public EngineRequirementExecutionProfileResolver(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotService snapshotService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            boolean openAiChatEnabled,
            ModelProviderProfileService providerProfileService,
            AgentToolPolicyService toolPolicyService,
            String contextProtocolVersion,
            boolean dynamicStateEnabled,
            int maxInjectedStateBytes
    ) {
        this(
                profileService,
                snapshotService,
                snapshotStore,
                openAiChatEnabled,
                providerProfileService,
                toolPolicyService,
                contextProtocolVersion,
                "LEGACY_OBSERVE_ONLY",
                dynamicStateEnabled,
                maxInjectedStateBytes
        );
    }

    public EngineRequirementExecutionProfileResolver(
            AgentExecutionProfileService profileService,
            AgentExecutionProfileSnapshotService snapshotService,
            AgentExecutionProfileSnapshotStore snapshotStore,
            boolean openAiChatEnabled,
            ModelProviderProfileService providerProfileService,
            AgentToolPolicyService toolPolicyService,
            String contextProtocolVersion,
            String contextPolicyMode,
            boolean dynamicStateEnabled,
            int maxInjectedStateBytes
    ) {
        this.profileService = Objects.requireNonNull(profileService, "profileService must not be null");
        this.snapshotService = Objects.requireNonNull(snapshotService, "snapshotService must not be null");
        this.snapshotStore = Objects.requireNonNull(snapshotStore, "snapshotStore must not be null");
        this.openAiChatEnabled = openAiChatEnabled;
        this.providerProfileService = providerProfileService;
        this.toolPolicyService = toolPolicyService;
        this.contextProtocolVersion = normalizeProtocolVersion(contextProtocolVersion);
        this.contextPolicyMode = normalizeContextPolicyMode(contextPolicyMode);
        this.dynamicStateEnabled = dynamicStateEnabled;
        this.maxInjectedStateBytes = maxInjectedStateBytes > 0 ? maxInjectedStateBytes : 8192;
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
            return RequirementExecutionProfileResolution.of(
                    existing.snapshotId(),
                    existing.snapshotJson()
            );
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
        return RequirementExecutionProfileResolution.of(stored.snapshotId(), stored.snapshotJson());
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
        AgentToolPolicy toolPolicy = resolveToolPolicy(runtimeType, profile, role);
        value.put("toolPolicyId", toolPolicy.policyId());
        value.put("toolPolicyVersion", toolPolicy.version());
        value.put("toolPolicy", toolPolicyJson(toolPolicy, dynamicStateEnabled));
        ModelProviderProfile provider = resolveProvider(runtimeType, profile);
        value.put("providerProtocol", provider == null ? "" : provider.protocol().name());
        value.put("providerBaseUrl", provider == null ? "" : provider.baseUrl());
        value.put("providerModelId", provider == null ? "" : provider.modelId());
        value.put("providerProfileVersion", provider == null ? 0L : provider.version());
        value.put("providerAuthHeader", provider != null && provider.authHeader());
        value.put("credentialEnvironmentVariable", provider == null ? "" : provider.credentialEnvironmentVariable());
        value.put("sessionPolicy", "ARCHIVE_NO_RESUME");
        value.put("resolvedFrom", profile == null ? "COMPATIBILITY_DEFAULT" : "REGISTERED_PROFILE");
        value.put("contextProtocolVersion", contextProtocolVersion);
        value.put("contextPolicyMode", contextPolicyMode);
        value.put("agentStateSchemaVersion", "rd-agent-state/v1");
        value.put("dynamicStateEnabled", dynamicStateEnabled);
        value.put("maxInjectedStateBytes", maxInjectedStateBytes);
        value.put("toolRetryPolicyVersion", "rd-tool-retry/v1");
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("AGENT_RUNTIME_SNAPSHOT_UNAVAILABLE: serialize snapshot", exception);
        }
    }

    private AgentToolPolicy resolveToolPolicy(
            AgentRuntimeType runtimeType,
            AgentExecutionProfile profile,
            AgentRole role
    ) {
        if (profile == null) {
            return defaultToolPolicy(runtimeType, role == null ? null : role.name());
        }
        if (toolPolicyService == null) {
            AgentToolPolicy fallback = defaultToolPolicy(runtimeType, profile.role());
            return new AgentToolPolicy(
                    profile.toolPolicyId(),
                    profile.toolPolicyVersion(),
                    fallback.hostAllow(),
                    fallback.allow(),
                    fallback.deny(),
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

    private static AgentToolPolicy defaultToolPolicy(AgentRuntimeType runtimeType, String role) {
        if (role != null && AgentRole.QA_AGENT.name().equalsIgnoreCase(role)) {
            return AgentToolPolicy.defaultQaPolicy();
        }
        return AgentToolPolicy.defaultCodingPolicy();
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

    private static final List<String> DYNAMIC_STATE_TOOLS = List.of(
            "rd_todo_rewrite",
            "rd_todo_update_status",
            "rd_record_fact"
    );

    private static Map<String, Object> toolPolicyJson(AgentToolPolicy policy) {
        return toolPolicyJson(policy, false);
    }

    /**
     * Serializes the frozen tool policy. When dynamic state is enabled, state tools are
     * merged into hostAllow/allow (and removed from deny) so Pi Bridge can register them.
     */
    private static Map<String, Object> toolPolicyJson(AgentToolPolicy policy, boolean dynamicStateEnabled) {
        java.util.LinkedHashSet<String> hostAllow = new java.util.LinkedHashSet<>(policy.hostAllow());
        java.util.LinkedHashSet<String> allow = new java.util.LinkedHashSet<>(policy.allow());
        java.util.LinkedHashSet<String> deny = new java.util.LinkedHashSet<>(policy.deny());
        if (dynamicStateEnabled) {
            for (String tool : DYNAMIC_STATE_TOOLS) {
                hostAllow.add(tool);
                allow.add(tool);
                deny.remove(tool);
            }
        }
        List<String> effective = allow.stream()
                .filter(hostAllow::contains)
                .filter(tool -> !deny.contains(tool))
                .sorted()
                .toList();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("hostAllow", hostAllow.stream().sorted().toList());
        value.put("allow", allow.stream().sorted().toList());
        value.put("deny", deny.stream().sorted().toList());
        value.put("effectiveAllow", effective);
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

    private static String normalizeProtocolVersion(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? "LEGACY_ENVIRONMENT_NOTES" : normalized;
    }

    private static String normalizeContextPolicyMode(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return "LEGACY_OBSERVE_ONLY";
        }
        String upper = normalized.toUpperCase();
        if ("LEGACY_OBSERVE_ONLY".equals(upper)
                || "ROOT_ONLY".equals(upper)
                || "ROOT_AND_ALLOWLISTED_NESTED".equals(upper)) {
            return upper;
        }
        throw new IllegalArgumentException(
                "contextPolicyMode must be LEGACY_OBSERVE_ONLY, ROOT_ONLY, or ROOT_AND_ALLOWLISTED_NESTED but was: "
                        + value
        );
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
