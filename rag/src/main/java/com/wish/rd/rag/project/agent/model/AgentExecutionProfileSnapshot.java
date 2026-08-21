package com.wish.rd.rag.project.agent.model;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** Immutable, hash-addressed runtime configuration resolved for one stage attempt. */
public record AgentExecutionProfileSnapshot(
        String snapshotId,
        String stageRunId,
        String taskId,
        String role,
        int attemptNo,
        AgentRuntimeType runtimeType,
        String snapshotJson,
        String snapshotHash,
        long resolvedAtEpochMillis
) {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public AgentExecutionProfileSnapshot {
        snapshotId = requireText(snapshotId, "snapshotId");
        stageRunId = requireText(stageRunId, "stageRunId");
        taskId = requireText(taskId, "taskId");
        role = requireText(role, "role").toUpperCase(Locale.ROOT);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (runtimeType == null) {
            throw new IllegalArgumentException("runtimeType must not be null");
        }
        snapshotJson = requireText(snapshotJson, "snapshotJson");
        snapshotHash = requireText(snapshotHash, "snapshotHash").toLowerCase(Locale.ROOT);
        if (resolvedAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("resolvedAtEpochMillis must be positive");
        }
    }

    public boolean hasValidIntegrityHash() {
        return snapshotHash.equals(sha256(snapshotJson));
    }

    /** Frozen profile version; legacy snapshots without the field resolve to zero. */
    public long profileVersion() {
        JsonNode node = snapshotPayload().get("profileVersion");
        if (node == null || node.isNull()) {
            return 0L;
        }
        if (!node.canConvertToLong() || node.longValue() < 0L) {
            throw new IllegalStateException("execution profile snapshot has invalid profileVersion");
        }
        return node.longValue();
    }

    /** Frozen, closed-set runtime capabilities; legacy snapshots default to disabled. */
    public List<AgentRuntimeCapability> capabilities() {
        JsonNode node = snapshotPayload().get("capabilities");
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (!node.isArray()) {
            throw new IllegalStateException("execution profile snapshot capabilities must be an array");
        }
        return java.util.stream.StreamSupport.stream(node.spliterator(), false)
                .map(item -> {
                    if (!item.isTextual()) {
                        throw new IllegalStateException(
                                "execution profile snapshot capability must be a string"
                        );
                    }
                    return AgentRuntimeCapability.parse(item.textValue());
                })
                .distinct()
                .sorted(java.util.Comparator.comparing(AgentRuntimeCapability::name))
                .toList();
    }

    public boolean hasCapability(AgentRuntimeCapability capability) {
        return capability != null && capabilities().contains(capability);
    }

    private JsonNode snapshotPayload() {
        try {
            JsonNode node = OBJECT_MAPPER.readTree(snapshotJson);
            if (node == null || !node.isObject()) {
                throw new IllegalStateException("execution profile snapshot payload must be an object");
            }
            return node;
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("invalid execution profile snapshot JSON", exception);
        }
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }
}
