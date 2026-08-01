package com.wish.rd.rag.project.agent.model;

import java.util.Objects;

/** Proposed or recorded agent state action. */
public record AgentStateAction(
        String actionId,
        long expectedSequence,
        long clientSequence,
        AgentStateActionDecision decision,
        String reason,
        String actionType
) {

    public static final int MAX_REASON_LENGTH = 512;

    public AgentStateAction {
        actionId = requireText(actionId, "actionId");
        if (expectedSequence < 0L) {
            throw new IllegalArgumentException("expectedSequence must be non-negative");
        }
        if (clientSequence < 0L) {
            throw new IllegalArgumentException("clientSequence must be non-negative");
        }
        Objects.requireNonNull(decision, "decision must not be null");
        reason = requireBoundedReason(reason);
        actionType = actionType == null || actionType.isBlank() ? null : actionType.strip();
    }

    private static String requireText(String value, String fieldName) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return normalized;
    }

    private static String requireBoundedReason(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        if (normalized.length() > MAX_REASON_LENGTH) {
            throw new IllegalArgumentException("reason exceeds max length " + MAX_REASON_LENGTH);
        }
        return normalized;
    }
}
