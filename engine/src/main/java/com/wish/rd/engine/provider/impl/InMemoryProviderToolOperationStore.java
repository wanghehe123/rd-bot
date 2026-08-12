package com.wish.rd.engine.provider.impl;

import com.wish.rd.engine.provider.ProviderToolOperationStore;
import com.wish.rd.engine.provider.model.ProviderToolOperation;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Thread-safe in-memory provider tool-operation ledger for tests and explicit memory mode.
 */
public final class InMemoryProviderToolOperationStore implements ProviderToolOperationStore {

    private final Map<String, ProviderToolOperation> operationsById = new LinkedHashMap<>();

    /**
     * Records a newer Host-owned observation without allowing a different task-stage identity to
     * replace an existing operation id.
     *
     * @param operation immutable operation evidence
     * @return the retained durable observation
     */
    @Override
    public synchronized ProviderToolOperation record(ProviderToolOperation operation) {
        if (operation == null) {
            throw new IllegalArgumentException("operation must not be null");
        }
        ProviderToolOperation current = operationsById.get(operation.operationId());
        if (current == null) {
            operationsById.put(operation.operationId(), operation);
            return operation;
        }
        if (!current.belongsTo(operation.taskId(), operation.stageRunId())
                || !current.attemptId().equals(operation.attemptId())) {
            throw new IllegalStateException("provider tool-operation identity mismatch: "
                    + operation.operationId());
        }
        if (operation.updatedAtEpochMillis() >= current.updatedAtEpochMillis()) {
            operationsById.put(operation.operationId(), operation);
            return operation;
        }
        return current;
    }

    /**
     * Finds the newest immutable observation for a task-stage boundary.
     *
     * @param taskId workflow task id
     * @param stageRunId stage-run id
     * @return latest operation observation when one exists
     */
    @Override
    public synchronized Optional<ProviderToolOperation> findLatestByTaskAndStageRun(
            String taskId,
            String stageRunId
    ) {
        String expectedTaskId = safe(taskId);
        String expectedStageRunId = safe(stageRunId);
        if (expectedTaskId.isBlank() || expectedStageRunId.isBlank()) {
            return Optional.empty();
        }
        return operationsById.values().stream()
                .filter(operation -> operation.belongsTo(expectedTaskId, expectedStageRunId))
                .max(Comparator.comparingLong(ProviderToolOperation::updatedAtEpochMillis)
                        .thenComparing(ProviderToolOperation::operationId));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
