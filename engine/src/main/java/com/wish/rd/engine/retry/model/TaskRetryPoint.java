package com.wish.rd.engine.retry.model;

import com.wish.rd.engine.agent.model.AgentRole;

/** Resolved, auditable point from which a failed task may continue. */
public record TaskRetryPoint(
        String taskId,
        TaskFailurePhase failurePhase,
        AgentRole retryFromRole,
        String failedStageRunId,
        String failedRetrievalRunId,
        String failedAiReviewRunId,
        String reason,
        long sourceTaskVersion
) {
    public TaskRetryPoint {
        taskId = requireText(taskId, "taskId");
        if (failurePhase == null) {
            throw new IllegalArgumentException("failurePhase must not be null");
        }
        failedStageRunId = safe(failedStageRunId);
        failedRetrievalRunId = safe(failedRetrievalRunId);
        failedAiReviewRunId = safe(failedAiReviewRunId);
        reason = safe(reason);
        sourceTaskVersion = Math.max(0L, sourceTaskVersion);
    }

    private static String requireText(String value, String field) {
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
