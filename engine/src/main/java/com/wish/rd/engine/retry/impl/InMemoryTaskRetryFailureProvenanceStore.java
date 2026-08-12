package com.wish.rd.engine.retry.impl;

import com.wish.rd.engine.retry.TaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.runtime.model.RdTaskStatus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Test/memory-mode structured failure provenance with immutable semantic replay. */
public final class InMemoryTaskRetryFailureProvenanceStore implements TaskRetryFailureProvenanceStore {

    private final Map<String, TaskRetryFailureProvenance> bySnapshot = new LinkedHashMap<>();
    private final Map<String, TaskRetryFailureProvenance> byCommandAttempt = new LinkedHashMap<>();
    private final Map<String, TaskRetryFailureProvenance> byId = new LinkedHashMap<>();

    @Override
    public synchronized TaskRetryFailureProvenance save(TaskRetryFailureProvenance provenance) {
        if (provenance == null) {
            throw new IllegalArgumentException("failure provenance must not be null");
        }
        String snapshotKey = snapshotKey(provenance.taskId(),
                provenance.failedTaskVersion(), provenance.failedTaskFencingToken());
        String commandKey = commandKey(provenance.failedStageCommandId(), provenance.failedCommandAttemptNo());
        TaskRetryFailureProvenance existing = byId.get(provenance.provenanceId());
        if (existing == null) {
            existing = bySnapshot.get(snapshotKey);
        }
        if (existing == null && !commandKey.isBlank()) {
            existing = byCommandAttempt.get(commandKey);
        }
        if (existing != null) {
            if (!existing.equals(provenance)) {
                throw new IllegalStateException("failure provenance identity conflict: " + provenance.provenanceId());
            }
            return existing;
        }
        bySnapshot.put(snapshotKey, provenance);
        byId.put(provenance.provenanceId(), provenance);
        if (!commandKey.isBlank()) {
            byCommandAttempt.put(commandKey, provenance);
        }
        return provenance;
    }

    /**
     * Returns any row already occupying the exact task version/fence snapshot key.
     *
     * @param taskId task identity
     * @param failedTaskVersion post-transition task version
     * @param failedTaskFencingToken post-transition fencing token
     * @return existing row when the snapshot key is occupied
     */
    public synchronized Optional<TaskRetryFailureProvenance> findByTaskVersionFence(
            String taskId,
            long failedTaskVersion,
            long failedTaskFencingToken
    ) {
        return Optional.ofNullable(bySnapshot.get(snapshotKey(taskId, failedTaskVersion, failedTaskFencingToken)));
    }

    /**
     * Returns provenance already bound to one exhausted command attempt.
     *
     * @param failedStageCommandId terminalized stage command id
     * @param failedCommandAttemptNo technical attempt number
     * @return existing row when present
     */
    public synchronized Optional<TaskRetryFailureProvenance> findByCommandAttempt(
            String failedStageCommandId,
            int failedCommandAttemptNo
    ) {
        String key = commandKey(failedStageCommandId, failedCommandAttemptNo);
        if (key.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byCommandAttempt.get(key));
    }

    /**
     * Removes one previously inserted row during memory-mode exhaustion rollback.
     *
     * @param provenance row to remove
     */
    public synchronized void removeForRollback(TaskRetryFailureProvenance provenance) {
        if (provenance == null) {
            return;
        }
        byId.remove(provenance.provenanceId());
        bySnapshot.remove(snapshotKey(
                provenance.taskId(), provenance.failedTaskVersion(), provenance.failedTaskFencingToken()));
        String commandKey = commandKey(provenance.failedStageCommandId(), provenance.failedCommandAttemptNo());
        if (!commandKey.isBlank()) {
            byCommandAttempt.remove(commandKey);
        }
    }

    @Override
    public synchronized Optional<TaskRetryFailureProvenance> findExact(
            String taskId,
            RdTaskStatus failedTaskStatus,
            long failedTaskVersion,
            long failedTaskFencingToken
    ) {
        return Optional.ofNullable(bySnapshot.get(snapshotKey(
                taskId, failedTaskVersion, failedTaskFencingToken)))
                .filter(provenance -> provenance.outcomeStatus() == failedTaskStatus);
    }

    private static String snapshotKey(String taskId, long version, long fence) {
        return safe(taskId) + ':' + version + ':' + fence;
    }

    private static String commandKey(String commandId, int attemptNo) {
        String normalized = safe(commandId);
        return normalized.isBlank() ? "" : normalized + ':' + attemptNo;
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
