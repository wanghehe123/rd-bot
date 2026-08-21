package com.wish.rd.engine.requirement.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;
import com.wish.rd.rag.project.agent.model.AgentRuntimeCapability;
import com.wish.rd.rag.project.agent.model.AgentRuntimeType;

import java.util.List;

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

    public boolean piStateV2Enabled() {
        JsonNode root = snapshotRoot();
        if (root == null
                || !dynamicStateEnabled
                || !AgentRuntimeType.PI.name().equals(root.path("runtimeType").asText())) {
            return false;
        }
        return hasCapability(root, AgentRuntimeCapability.PI_AGENT_STATE_V2);
    }

    /** Frozen PI QA remediation-v2 decision for this exact stage attempt. */
    public boolean piQaRemediationV2Enabled() {
        JsonNode root = snapshotRoot();
        return root != null
                && AgentRuntimeType.PI.name().equals(root.path("runtimeType").asText())
                && hasCapability(root, AgentRuntimeCapability.PI_QA_REMEDIATION_V2);
    }

    public int maxInjectedStateBytes() {
        JsonNode root = snapshotRoot();
        int value = root == null ? 0 : root.path("maxInjectedStateBytes").asInt(0);
        return value > 0 ? value : 8192;
    }

    private JsonNode snapshotRoot() {
        if (snapshotJson.isBlank()) {
            return null;
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(snapshotJson);
            return root != null && root.isObject() ? root : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean hasCapability(JsonNode root, AgentRuntimeCapability expected) {
        JsonNode capabilities = root.path("capabilities");
        if (!capabilities.isArray()) return false;
        for (JsonNode capability : capabilities) {
            if (capability.isTextual() && expected.name().equals(capability.textValue())) return true;
        }
        return false;
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
