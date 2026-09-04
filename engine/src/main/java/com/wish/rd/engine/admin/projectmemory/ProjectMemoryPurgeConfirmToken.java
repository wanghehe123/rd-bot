package com.wish.rd.engine.admin.projectmemory;

import java.util.Objects;

/** Short-lived confirmation token issued after an authorized purge preview. */
public record ProjectMemoryPurgeConfirmToken(
        String token,
        String projectId,
        String operatorId,
        String reason,
        int expectedTotalRowCount,
        long expiresAtEpochMillis
) {
    public ProjectMemoryPurgeConfirmToken {
        token = required(token, "token");
        projectId = required(projectId, "projectId");
        operatorId = required(operatorId, "operatorId");
        reason = required(reason, "reason");
        if (expectedTotalRowCount < 0) {
            throw new IllegalArgumentException("expectedTotalRowCount must not be negative");
        }
        if (expiresAtEpochMillis <= 0L) {
            throw new IllegalArgumentException("expiresAtEpochMillis must be positive");
        }
    }

    public boolean expiredAt(long nowEpochMillis) {
        return nowEpochMillis >= expiresAtEpochMillis;
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return normalized;
    }
}
