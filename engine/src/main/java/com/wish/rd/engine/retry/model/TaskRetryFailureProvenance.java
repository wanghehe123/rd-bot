package com.wish.rd.engine.retry.model;

import com.wish.rd.rag.runtime.model.RdTaskStatus;

/** Immutable identity of the exact durable failure from which a business retry may start. */
public record TaskRetryFailureProvenance(
        String provenanceId,
        String taskId,
        String failedStageCommandId,
        int failedCommandAttemptNo,
        String failedStage,
        TaskFailurePhase failurePhase,
        RdTaskStatus outcomeStatus,
        long failedTaskVersion,
        long failedTaskFencingToken,
        String failedStageRunId,
        String failedRetrievalRunId,
        String failedAiReviewRunId,
        String sourcePolicyRunId,
        String sourcePlanDigest,
        String publicationOperationId,
        String failureKind,
        long recordedAtEpochMillis,
        String failedVerificationRunId
) {

    public TaskRetryFailureProvenance {
        provenanceId = require(provenanceId, "provenanceId");
        taskId = require(taskId, "taskId");
        failedStageCommandId = safe(failedStageCommandId);
        failedCommandAttemptNo = Math.max(0, failedCommandAttemptNo);
        failedStage = safe(failedStage);
        if (failurePhase == null) {
            throw new IllegalArgumentException("failurePhase must not be null");
        }
        if (outcomeStatus == null) {
            throw new IllegalArgumentException("outcomeStatus must not be null");
        }
        failedTaskVersion = Math.max(0L, failedTaskVersion);
        failedTaskFencingToken = Math.max(0L, failedTaskFencingToken);
        failedStageRunId = safe(failedStageRunId);
        failedRetrievalRunId = safe(failedRetrievalRunId);
        failedAiReviewRunId = safe(failedAiReviewRunId);
        sourcePolicyRunId = safe(sourcePolicyRunId);
        sourcePlanDigest = safe(sourcePlanDigest);
        publicationOperationId = safe(publicationOperationId);
        failureKind = safe(failureKind);
        recordedAtEpochMillis = Math.max(0L, recordedAtEpochMillis);
        failedVerificationRunId = safe(failedVerificationRunId);
    }

    /**
     * Compatibility constructor for rows written before host-verify identity existed.
     *
     * <p>Old callers keep compiling; {@code failedVerificationRunId} defaults to blank.
     */
    public TaskRetryFailureProvenance(
            String provenanceId,
            String taskId,
            String failedStageCommandId,
            int failedCommandAttemptNo,
            String failedStage,
            TaskFailurePhase failurePhase,
            RdTaskStatus outcomeStatus,
            long failedTaskVersion,
            long failedTaskFencingToken,
            String failedStageRunId,
            String failedRetrievalRunId,
            String failedAiReviewRunId,
            String sourcePolicyRunId,
            String sourcePlanDigest,
            String publicationOperationId,
            String failureKind,
            long recordedAtEpochMillis
    ) {
        this(provenanceId, taskId, failedStageCommandId, failedCommandAttemptNo, failedStage,
                failurePhase, outcomeStatus, failedTaskVersion, failedTaskFencingToken,
                failedStageRunId, failedRetrievalRunId, failedAiReviewRunId,
                sourcePolicyRunId, sourcePlanDigest, publicationOperationId,
                failureKind, recordedAtEpochMillis, "");
    }

    /** Compatibility constructor for provenance written before exact attempt IDs were added. */
    public TaskRetryFailureProvenance(
            String provenanceId,
            String taskId,
            String failedStageCommandId,
            int failedCommandAttemptNo,
            String failedStage,
            TaskFailurePhase failurePhase,
            RdTaskStatus outcomeStatus,
            long failedTaskVersion,
            long failedTaskFencingToken,
            String sourcePolicyRunId,
            String sourcePlanDigest,
            String publicationOperationId,
            String failureKind,
            long recordedAtEpochMillis
    ) {
        this(provenanceId, taskId, failedStageCommandId, failedCommandAttemptNo, failedStage,
                failurePhase, outcomeStatus, failedTaskVersion, failedTaskFencingToken,
                "", "", "", sourcePolicyRunId, sourcePlanDigest, publicationOperationId,
                failureKind, recordedAtEpochMillis, "");
    }

    /** Returns whether this row is the exact failed task snapshot currently being retried. */
    public boolean matches(String expectedTaskId, RdTaskStatus status, long version, long fencingToken) {
        return taskId.equals(safe(expectedTaskId))
                && outcomeStatus == status
                && failedTaskVersion == version
                && failedTaskFencingToken == fencingToken;
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
