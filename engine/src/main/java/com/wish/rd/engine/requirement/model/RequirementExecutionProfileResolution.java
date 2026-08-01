package com.wish.rd.engine.requirement.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;

/** Result of resolving the immutable execution profile for one stage attempt. */
public record RequirementExecutionProfileResolution(
        String snapshotId,
        String snapshotJson,
        String contextProtocolVersion,
        boolean dynamicStateEnabled,
        String contextPolicyMode
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROTOCOL = ContextProtocolVersion.LEGACY_ENVIRONMENT_NOTES.name();
    private static final String DEFAULT_CONTEXT_POLICY_MODE = "LEGACY_OBSERVE_ONLY";

    public RequirementExecutionProfileResolution {
        snapshotId = snapshotId == null ? "" : snapshotId.strip();
        snapshotJson = snapshotJson == null ? "" : snapshotJson;
        contextProtocolVersion = normalizeProtocol(contextProtocolVersion);
        contextPolicyMode = normalizeContextPolicyMode(contextPolicyMode);
    }

    public boolean resolved() {
        return !snapshotId.isBlank();
    }

    public ContextProtocolVersion protocolVersion() {
        return ContextProtocolVersion.parse(contextProtocolVersion);
    }

    public static RequirementExecutionProfileResolution none() {
        return new RequirementExecutionProfileResolution(
                "", "", DEFAULT_PROTOCOL, false, DEFAULT_CONTEXT_POLICY_MODE);
    }

    public static RequirementExecutionProfileResolution of(String snapshotId, String snapshotJson) {
        String normalizedJson = snapshotJson == null ? "" : snapshotJson;
        if (normalizedJson.isBlank()) {
            return new RequirementExecutionProfileResolution(
                    snapshotId, "", DEFAULT_PROTOCOL, false, DEFAULT_CONTEXT_POLICY_MODE);
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(normalizedJson);
            String protocol = root.path("contextProtocolVersion").asText(DEFAULT_PROTOCOL);
            boolean dynamic = root.path("dynamicStateEnabled").asBoolean(false);
            String policyMode = root.path("contextPolicyMode").asText(DEFAULT_CONTEXT_POLICY_MODE);
            return new RequirementExecutionProfileResolution(
                    snapshotId, normalizedJson, protocol, dynamic, policyMode);
        } catch (Exception ignored) {
            return new RequirementExecutionProfileResolution(
                    snapshotId, normalizedJson, DEFAULT_PROTOCOL, false, DEFAULT_CONTEXT_POLICY_MODE);
        }
    }

    private static String normalizeProtocol(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? DEFAULT_PROTOCOL : normalized;
    }

    private static String normalizeContextPolicyMode(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            return DEFAULT_CONTEXT_POLICY_MODE;
        }
        return normalized.toUpperCase();
    }
}
