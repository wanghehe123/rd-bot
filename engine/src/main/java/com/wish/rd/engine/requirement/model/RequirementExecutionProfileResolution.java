package com.wish.rd.engine.requirement.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.rag.project.agent.model.ContextProtocolVersion;

/** Result of resolving the immutable execution profile for one stage attempt. */
public record RequirementExecutionProfileResolution(
        String snapshotId,
        String snapshotJson,
        String contextProtocolVersion,
        boolean dynamicStateEnabled
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DEFAULT_PROTOCOL = ContextProtocolVersion.LEGACY_ENVIRONMENT_NOTES.name();

    public RequirementExecutionProfileResolution {
        snapshotId = snapshotId == null ? "" : snapshotId.strip();
        snapshotJson = snapshotJson == null ? "" : snapshotJson;
        contextProtocolVersion = normalizeProtocol(contextProtocolVersion);
    }

    public boolean resolved() {
        return !snapshotId.isBlank();
    }

    public ContextProtocolVersion protocolVersion() {
        return ContextProtocolVersion.parse(contextProtocolVersion);
    }

    public static RequirementExecutionProfileResolution none() {
        return new RequirementExecutionProfileResolution("", "", DEFAULT_PROTOCOL, false);
    }

    public static RequirementExecutionProfileResolution of(String snapshotId, String snapshotJson) {
        String normalizedJson = snapshotJson == null ? "" : snapshotJson;
        if (normalizedJson.isBlank()) {
            return new RequirementExecutionProfileResolution(snapshotId, "", DEFAULT_PROTOCOL, false);
        }
        try {
            JsonNode root = OBJECT_MAPPER.readTree(normalizedJson);
            String protocol = root.path("contextProtocolVersion").asText(DEFAULT_PROTOCOL);
            boolean dynamic = root.path("dynamicStateEnabled").asBoolean(false);
            return new RequirementExecutionProfileResolution(snapshotId, normalizedJson, protocol, dynamic);
        } catch (Exception ignored) {
            return new RequirementExecutionProfileResolution(snapshotId, normalizedJson, DEFAULT_PROTOCOL, false);
        }
    }

    private static String normalizeProtocol(String value) {
        String normalized = value == null ? "" : value.strip();
        return normalized.isBlank() ? DEFAULT_PROTOCOL : normalized;
    }
}
