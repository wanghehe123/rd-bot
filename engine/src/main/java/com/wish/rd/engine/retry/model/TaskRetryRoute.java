package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.List;

/** Immutable first-command route for one exact retry failure provenance. */
public record TaskRetryRoute(
        RdTaskStatus initialExpectedStatus,
        String role,
        String firstStage,
        TaskRetryAttemptKind primaryAttemptKind,
        TaskRetryAttemptKind associatedAttemptKind,
        List<AgentRole> precreatedRoles
) {
    public TaskRetryRoute {
        if (initialExpectedStatus != RdTaskStatus.RECOVERING) {
            throw new IllegalArgumentException("retry route must start from RECOVERING");
        }
        role = require(role, "role");
        firstStage = require(firstStage, "firstStage");
        precreatedRoles = List.copyOf(precreatedRoles == null ? List.of() : precreatedRoles);
    }

    private static String require(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
