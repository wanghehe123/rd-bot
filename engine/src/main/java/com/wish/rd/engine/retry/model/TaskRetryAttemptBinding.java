package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.agent.model.AgentRole;

/** Immutable checkpoint-scoped identity for one retry attempt or child attempt. */
public record TaskRetryAttemptBinding(
        String bindingId,
        String checkpointId,
        TaskRetryAttemptKind kind,
        AgentRole role,
        String attemptId,
        String parentBindingId,
        int attemptNo,
        int ordinal
) {

    public TaskRetryAttemptBinding {
        bindingId = require(bindingId, "bindingId");
        checkpointId = require(checkpointId, "checkpointId");
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        attemptId = require(attemptId, "attemptId");
        parentBindingId = safe(parentBindingId);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        if (ordinal < 0) {
            throw new IllegalArgumentException("ordinal must not be negative");
        }
        if (kind == TaskRetryAttemptKind.AGENT_STAGE) {
            if (role == null) {
                throw new IllegalArgumentException("agent-stage binding role must not be null");
            }
            if (!parentBindingId.isBlank()) {
                throw new IllegalArgumentException("agent-stage binding must not have a parent");
            }
        } else if (kind == TaskRetryAttemptKind.RETRIEVAL) {
            if (role == null || parentBindingId.isBlank()) {
                throw new IllegalArgumentException("retrieval binding requires role and parent binding");
            }
        } else if (role == null && !parentBindingId.isBlank()) {
            throw new IllegalArgumentException("child AI-review binding requires a role");
        }
    }

    private static String require(String value, String field) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
