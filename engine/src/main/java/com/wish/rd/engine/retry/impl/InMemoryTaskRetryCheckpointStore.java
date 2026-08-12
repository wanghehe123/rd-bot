package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Test-only in-memory retry checkpoint store with idempotent create semantics. */
public final class InMemoryTaskRetryCheckpointStore implements TaskRetryCheckpointStore {

    private final Map<String, TaskRetryCheckpoint> checkpoints = new LinkedHashMap<>();

    @Override
    public synchronized CreateResult createOrGet(TaskRetryCheckpoint checkpoint) {
        TaskRetryCheckpoint existing = checkpoints.values().stream()
                .filter(value -> value.idempotencyKey().equals(checkpoint.idempotencyKey()))
                .findFirst().orElse(null);
        if (existing != null) {
            requireSameReplayIdentity(checkpoint, existing);
            return new CreateResult(existing, false);
        }
        if (checkpoints.containsKey(checkpoint.checkpointId())) {
            throw new IllegalStateException("retry checkpoint already exists: " + checkpoint.checkpointId());
        }
        boolean activeCheckpointExists = checkpoints.values().stream()
                .anyMatch(existingCheckpoint -> existingCheckpoint.taskId().equals(checkpoint.taskId())
                        && isActive(existingCheckpoint.status()));
        if (activeCheckpointExists) {
            throw new IllegalStateException("task already has an active retry checkpoint: " + checkpoint.taskId());
        }
        checkpoints.put(checkpoint.checkpointId(), checkpoint);
        return new CreateResult(checkpoint, true);
    }

    @Override
    public synchronized Optional<TaskRetryCheckpoint> find(String checkpointId) {
        return Optional.ofNullable(checkpoints.get(normalize(checkpointId)));
    }

    @Override
    public synchronized Optional<TaskRetryCheckpoint> findActiveByTask(String taskId) {
        String normalized = normalize(taskId);
        return checkpoints.values().stream()
                .filter(checkpoint -> checkpoint.taskId().equals(normalized))
                .filter(checkpoint -> isActive(checkpoint.status()))
                .max(Comparator.comparingInt(TaskRetryCheckpoint::attemptNo)
                        .thenComparingLong(TaskRetryCheckpoint::createdAtEpochMillis));
    }

    @Override
    public synchronized List<TaskRetryCheckpoint> listByTask(String taskId) {
        String normalized = normalize(taskId);
        return checkpoints.values().stream()
                .filter(checkpoint -> checkpoint.taskId().equals(normalized))
                .sorted(Comparator.comparingInt(TaskRetryCheckpoint::attemptNo))
                .toList();
    }

    @Override
    public synchronized TaskRetryCheckpoint transition(
            String checkpointId,
            TaskRetryCheckpointStatus expectedStatus,
            TaskRetryCheckpointStatus targetStatus,
            String errorMessage,
            long nowEpochMillis
    ) {
        TaskRetryCheckpoint current = checkpoints.get(normalize(checkpointId));
        if (current == null) {
            throw new java.util.NoSuchElementException("retry checkpoint not found: " + checkpointId);
        }
        if (current.status() != expectedStatus) {
            throw new IllegalStateException("stale retry checkpoint status: expected " + expectedStatus
                    + " but was " + current.status());
        }
        if (current.status().isTerminal()) {
            throw new IllegalStateException("terminal retry checkpoint is immutable: " + checkpointId);
        }
        if (!allows(current.status(), targetStatus)) {
            throw new IllegalStateException("illegal retry checkpoint transition: "
                    + current.status() + " -> " + targetStatus);
        }
        TaskRetryCheckpoint updated = current.withStatus(targetStatus, errorMessage, nowEpochMillis);
        checkpoints.put(updated.checkpointId(), updated);
        return updated;
    }

    /**
     * Restores an exact checkpoint snapshot during memory-mode exhaustion rollback.
     *
     * @param snapshot checkpoint state observed before the failed atomic unit
     */
    public synchronized void restoreSnapshot(TaskRetryCheckpoint snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("checkpoint snapshot must not be null");
        }
        checkpoints.put(snapshot.checkpointId(), snapshot);
    }

    @Override
    public synchronized TaskRetryCheckpoint dispatch(
            String checkpointId,
            long dispatchTaskVersion,
            long dispatchFencingToken,
            String dispatchCommandId,
            long nowEpochMillis
    ) {
        TaskRetryCheckpoint current = checkpoints.get(normalize(checkpointId));
        if (current == null) {
            throw new java.util.NoSuchElementException("retry checkpoint not found: " + checkpointId);
        }
        TaskRetryCheckpoint updated = current.dispatched(
                dispatchTaskVersion, dispatchFencingToken, dispatchCommandId, nowEpochMillis);
        checkpoints.put(updated.checkpointId(), updated);
        return updated;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean allows(TaskRetryCheckpointStatus source, TaskRetryCheckpointStatus target) {
        return source == TaskRetryCheckpointStatus.CREATED
                ? target == TaskRetryCheckpointStatus.DISPATCHED
                || target == TaskRetryCheckpointStatus.FAILED_RETRYABLE
                || target == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                || target == TaskRetryCheckpointStatus.CANCELLED
                : source == TaskRetryCheckpointStatus.DISPATCHED
                ? target == TaskRetryCheckpointStatus.WAITING_APPROVAL
                || target == TaskRetryCheckpointStatus.SUCCEEDED
                || target == TaskRetryCheckpointStatus.FAILED_RETRYABLE
                || target == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                || target == TaskRetryCheckpointStatus.CANCELLED
                : source == TaskRetryCheckpointStatus.WAITING_APPROVAL
                && (target == TaskRetryCheckpointStatus.DISPATCHED
                || target == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                || target == TaskRetryCheckpointStatus.CANCELLED);
    }

    private static boolean isActive(TaskRetryCheckpointStatus status) {
        return status == TaskRetryCheckpointStatus.CREATED
                || status == TaskRetryCheckpointStatus.DISPATCHED
                || status == TaskRetryCheckpointStatus.WAITING_APPROVAL;
    }

    /** Mirrors PostgreSQL replay semantics while leaving winner-owned lifecycle values untouched. */
    private static void requireSameReplayIdentity(TaskRetryCheckpoint requested, TaskRetryCheckpoint existing) {
        boolean same = requested.taskId().equals(existing.taskId())
                && requested.failurePhase() == existing.failurePhase()
                && requested.retryFromRole() == existing.retryFromRole()
                && requested.failedStageRunId().equals(existing.failedStageRunId())
                && requested.failedRetrievalRunId().equals(existing.failedRetrievalRunId())
                && requested.failedAiReviewRunId().equals(existing.failedAiReviewRunId())
                && requested.attemptNo() == existing.attemptNo()
                && requested.sourceTaskStatus() == existing.sourceTaskStatus()
                && requested.sourceTaskVersion() == existing.sourceTaskVersion()
                && requested.sourceFencingToken() == existing.sourceFencingToken()
                && requested.failedStageCommandId().equals(existing.failedStageCommandId())
                && requested.failedStage().equals(existing.failedStage())
                && requested.sourcePolicyRunId().equals(existing.sourcePolicyRunId())
                && requested.sourcePlanDigest().equals(existing.sourcePlanDigest())
                && requested.publicationOperationId().equals(existing.publicationOperationId())
                && requested.operatorNote().equals(existing.operatorNote())
                && requested.evidenceMaterialIds().equals(existing.evidenceMaterialIds())
                && requested.reason().equals(existing.reason());
        if (!same) {
            throw new IllegalStateException("retry checkpoint idempotency conflict has different immutable source or route");
        }
    }
}
