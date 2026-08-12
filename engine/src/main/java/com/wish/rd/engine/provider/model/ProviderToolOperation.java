package com.wish.rd.engine.provider.model;

/**
 * Host-owned durable evidence for one provider tool operation.
 *
 * <p>The record is deliberately separate from publication because a repository, browser, or
 * other tool may create an externally relevant effect before any publication intent exists.
 */
public record ProviderToolOperation(
        String operationId,
        String taskId,
        String stageRunId,
        String attemptId,
        Status status,
        String reason,
        long createdAtEpochMillis,
        long updatedAtEpochMillis
) {

    /** States for the authoritative tool-operation lifecycle. */
    public enum Status {
        /** Host persisted an operation intent but has not reconciled its effects. */
        PREPARED,
        /** Host reconciled that the operation finished without a retained external effect. */
        CLEANLY_ABORTED,
        /** Host cannot establish whether a remote side effect was applied. */
        UNKNOWN_REMOTE_RESULT,
        /** Host confirmed that the operation committed a side effect. */
        COMMITTED,
        /** Conflicting evidence requires an operator to resolve the operation. */
        NEEDS_HUMAN
    }

    /**
     * Normalizes durable identifiers and timestamps.
     */
    public ProviderToolOperation {
        operationId = require(operationId, "operationId");
        taskId = require(taskId, "taskId");
        stageRunId = require(stageRunId, "stageRunId");
        attemptId = require(attemptId, "attemptId");
        status = status == null ? Status.UNKNOWN_REMOTE_RESULT : status;
        reason = safe(reason);
        createdAtEpochMillis = Math.max(0L, createdAtEpochMillis);
        updatedAtEpochMillis = Math.max(createdAtEpochMillis, updatedAtEpochMillis);
    }

    /**
     * Returns whether this operation is authoritative evidence that the prior attempt left no
     * side effect that an alternate provider could duplicate.
     *
     * @return true only after Host reconciliation reached {@link Status#CLEANLY_ABORTED}
     */
    public boolean permitsFallback() {
        return status == Status.CLEANLY_ABORTED;
    }

    /**
     * Returns whether this operation belongs to the supplied task and stage boundary.
     *
     * @param expectedTaskId expected workflow task id
     * @param expectedStageRunId expected stage-run id
     * @return true when both durable identities match
     */
    public boolean belongsTo(String expectedTaskId, String expectedStageRunId) {
        return taskId.equals(safe(expectedTaskId)) && stageRunId.equals(safe(expectedStageRunId));
    }

    private static String require(String value, String name) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return normalized;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
