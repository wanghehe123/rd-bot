package com.wish.rd.rag.project.agent.model;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** A registered, selectable execution strategy for one project and role. */
public record AgentExecutionProfile(
        String profileId,
        String projectId,
        String role,
        String name,
        AgentRuntimeType runtimeType,
        String providerProfileId,
        String modelOverride,
        String extensionSetId,
        long extensionSetVersion,
        String toolPolicyId,
        long toolPolicyVersion,
        boolean enabled,
        long version,
        List<AgentRuntimeCapability> capabilities
) {

    public AgentExecutionProfile {
        profileId = text(profileId);
        projectId = text(projectId);
        role = text(role).toUpperCase(Locale.ROOT);
        name = text(name);
        if (runtimeType == null) {
            throw new IllegalArgumentException("runtimeType must not be null");
        }
        providerProfileId = text(providerProfileId);
        modelOverride = text(modelOverride);
        extensionSetId = text(extensionSetId);
        extensionSetVersion = Math.max(0L, extensionSetVersion);
        toolPolicyId = text(toolPolicyId);
        toolPolicyVersion = toolPolicyVersion <= 0L ? 1L : toolPolicyVersion;
        version = version <= 0L ? 1L : version;
        capabilities = capabilities == null
                ? List.of()
                : capabilities.stream()
                        .distinct()
                        .sorted(Comparator.comparing(AgentRuntimeCapability::name))
                        .toList();
    }

    /** Backward-compatible full constructor; runtime capabilities default to empty. */
    public AgentExecutionProfile(
            String profileId,
            String projectId,
            String role,
            String name,
            AgentRuntimeType runtimeType,
            String providerProfileId,
            String modelOverride,
            String extensionSetId,
            long extensionSetVersion,
            String toolPolicyId,
            long toolPolicyVersion,
            boolean enabled,
            long version
    ) {
        this(
                profileId,
                projectId,
                role,
                name,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                extensionSetVersion,
                toolPolicyId,
                toolPolicyVersion,
                enabled,
                version,
                List.of()
        );
    }

    /** Backward-compatible profile constructor; policies default to version 1. */
    public AgentExecutionProfile(
            String profileId,
            String projectId,
            String role,
            String name,
            AgentRuntimeType runtimeType,
            String providerProfileId,
            String modelOverride,
            String extensionSetId,
            String toolPolicyId,
            boolean enabled,
            long version
    ) {
        this(
                profileId,
                projectId,
                role,
                name,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                0L,
                toolPolicyId,
                1L,
                enabled,
                version,
                List.of()
        );
    }

    /** Compact constructor with explicit runtime capabilities. */
    public AgentExecutionProfile(
            String profileId,
            String projectId,
            String role,
            String name,
            AgentRuntimeType runtimeType,
            String providerProfileId,
            String modelOverride,
            String extensionSetId,
            String toolPolicyId,
            boolean enabled,
            long version,
            List<AgentRuntimeCapability> capabilities
    ) {
        this(
                profileId,
                projectId,
                role,
                name,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                0L,
                toolPolicyId,
                1L,
                enabled,
                version,
                capabilities
        );
    }

    public boolean hasCapability(AgentRuntimeCapability capability) {
        return capability != null && capabilities.contains(capability);
    }

    public AgentExecutionProfile withVersion(long nextVersion) {
        return new AgentExecutionProfile(
                profileId,
                projectId,
                role,
                name,
                runtimeType,
                providerProfileId,
                modelOverride,
                extensionSetId,
                extensionSetVersion,
                toolPolicyId,
                toolPolicyVersion,
                enabled,
                nextVersion,
                capabilities
        );
    }

    private static String text(String value) {
        return value == null ? "" : value.strip();
    }
}
