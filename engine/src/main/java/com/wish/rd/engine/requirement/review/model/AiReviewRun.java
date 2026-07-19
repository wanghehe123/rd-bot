package com.wish.rd.engine.requirement.review.model;

/** Immutable snapshot of one AI delivery-review attempt. */
public record AiReviewRun(
        String runId,
        String taskId,
        int attemptNo,
        String parentRunId,
        AiReviewRunStatus status,
        String modelName,
        String packageHash,
        AiReviewDecision decision,
        int score,
        String retryFromRole,
        String summary,
        String errorCategory,
        String errorMessage,
        long version,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {
    public AiReviewRun {
        runId = requireText(runId, "runId");
        taskId = requireText(taskId, "taskId");
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo must be positive");
        }
        parentRunId = safe(parentRunId);
        status = status == null ? AiReviewRunStatus.CREATED : status;
        modelName = safe(modelName);
        packageHash = safe(packageHash);
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score must be between 0 and 100");
        }
        retryFromRole = safe(retryFromRole);
        summary = safe(summary);
        errorCategory = safe(errorCategory);
        errorMessage = safe(errorMessage);
        version = Math.max(0L, version);
    }

    public static AiReviewRun created(
            String runId,
            String taskId,
            int attemptNo,
            String parentRunId,
            String modelName,
            long nowEpochMillis
    ) {
        return new AiReviewRun(runId, taskId, attemptNo, parentRunId, AiReviewRunStatus.CREATED,
                modelName, "", null, 0, "", "", "", "", 0L, nowEpochMillis, nowEpochMillis);
    }

    public AiReviewRun withStatus(
            AiReviewRunStatus target,
            String newErrorCategory,
            String newErrorMessage,
            long nowEpochMillis
    ) {
        return new AiReviewRun(runId, taskId, attemptNo, parentRunId, target, modelName,
                packageHash, decision, score, retryFromRole, summary,
                newErrorCategory, newErrorMessage, version + 1L, createdAtEpochMillis, nowEpochMillis);
    }

    public AiReviewRun withPackageHash(String newPackageHash, long nowEpochMillis) {
        return new AiReviewRun(runId, taskId, attemptNo, parentRunId, status, modelName,
                newPackageHash, decision, score, retryFromRole, summary, errorCategory, errorMessage,
                version + 1L, createdAtEpochMillis, nowEpochMillis);
    }

    public AiReviewRun withResult(AiReviewResult result, AiReviewRunStatus terminalStatus, long nowEpochMillis) {
        if (result == null) {
            throw new IllegalArgumentException("result must not be null");
        }
        return new AiReviewRun(runId, taskId, attemptNo, parentRunId, terminalStatus, modelName,
                packageHash, result.decision(), result.score(),
                result.retryFromRole() == null ? "" : result.retryFromRole().name(),
                result.summary(), "", "", version + 1L, createdAtEpochMillis, nowEpochMillis);
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
