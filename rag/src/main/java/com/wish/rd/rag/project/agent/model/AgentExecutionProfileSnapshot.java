package com.wish.rd.rag.project.agent.model;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
