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
            return new CreateResult(existing, false);
        }
        if (checkpoints.containsKey(checkpoint.checkpointId())) {
            throw new IllegalStateException("retry checkpoint already exists: " + checkpoint.checkpointId());
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
                .filter(checkpoint -> checkpoint.status() == TaskRetryCheckpointStatus.CREATED
                        || checkpoint.status() == TaskRetryCheckpointStatus.DISPATCHED)
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
        TaskRetryCheckpoint updated = current.withStatus(targetStatus, errorMessage, nowEpochMillis);
        checkpoints.put(updated.checkpointId(), updated);
        return updated;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
