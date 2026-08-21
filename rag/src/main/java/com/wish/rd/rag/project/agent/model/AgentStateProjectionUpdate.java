package com.wish.rd.rag.project.agent.model;

/** One independently monotonic live-state projection update. */
public record AgentStateProjectionUpdate(
        AgentStageStateIdentity identity,
        long stateSequence,
        String stateHash,
        String stateJson,
        long projectedAtEpochMillis
) {
    public AgentStateProjectionUpdate {
        if (identity == null) throw new IllegalArgumentException("identity must not be null");
        if (stateSequence < 0) throw new IllegalArgumentException("stateSequence must not be negative");
        stateHash = requireHash(stateHash, "stateHash");
        stateJson = requireText(stateJson, "stateJson");
        if (projectedAtEpochMillis < 1) {
            throw new IllegalArgumentException("projectedAtEpochMillis must be positive");
        }
    }

    static String requireHash(String value, String field) {
        String normalized = requireText(value, field).toLowerCase();
        if (!normalized.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a sha256 hash");
        }
        return normalized;
    }

    static String requireText(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
