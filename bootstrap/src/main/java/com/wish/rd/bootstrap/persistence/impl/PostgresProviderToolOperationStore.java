package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.ProviderToolOperationRow;
import com.wish.rd.bootstrap.persistence.mapper.ProviderToolOperationMapper;
import com.wish.rd.engine.provider.ProviderToolOperationStore;
import com.wish.rd.engine.provider.model.ProviderToolOperation;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/** PostgreSQL implementation of the authoritative provider tool-operation ledger. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresProviderToolOperationStore implements ProviderToolOperationStore {

    private final ProviderToolOperationMapper mapper;

    /**
     * Creates the PostgreSQL operation ledger adapter.
     *
     * @param mapper durable operation mapper
     */
    public PostgresProviderToolOperationStore(ProviderToolOperationMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper must not be null");
    }

    /**
     * Persists an operation observation and rejects conflicting reuse of its immutable id.
     *
     * @param operation Host-owned operation evidence
     * @return retained durable evidence
     */
    @Override
    @Transactional
    public ProviderToolOperation record(ProviderToolOperation operation) {
        Objects.requireNonNull(operation, "operation must not be null");
        int affected = mapper.record(toRow(operation));
        ProviderToolOperationRow persisted = mapper.findByOperationId(operation.operationId());
        if (persisted == null) {
            throw new IllegalStateException("provider tool-operation disappeared: " + operation.operationId());
        }
        ProviderToolOperation retained = toOperation(persisted);
        if (!retained.belongsTo(operation.taskId(), operation.stageRunId())
                || !retained.attemptId().equals(operation.attemptId())) {
            throw new IllegalStateException("provider tool-operation identity mismatch: "
                    + operation.operationId());
        }
        if (affected == 0 && retained.updatedAtEpochMillis() < operation.updatedAtEpochMillis()) {
            throw new IllegalStateException("provider tool-operation compare-and-set failed: "
                    + operation.operationId());
        }
        return retained;
    }

    /**
     * Reads the newest operation evidence for a task-stage boundary.
     *
     * @param taskId workflow task id
     * @param stageRunId stage-run id
     * @return newest operation evidence when present
     */
    @Override
    public Optional<ProviderToolOperation> findLatestByTaskAndStageRun(String taskId, String stageRunId) {
        String normalizedTaskId = safe(taskId);
        String normalizedStageRunId = safe(stageRunId);
        if (normalizedTaskId.isBlank() || normalizedStageRunId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(mapper.findLatestByTaskAndStageRun(
                PostgresPersistenceSupport.parseId(normalizedTaskId), normalizedStageRunId
        )).map(this::toOperation);
    }

    private ProviderToolOperationRow toRow(ProviderToolOperation operation) {
        ProviderToolOperationRow row = new ProviderToolOperationRow();
        row.operationId = operation.operationId();
        row.taskId = PostgresPersistenceSupport.parseId(operation.taskId());
        row.stageRunId = operation.stageRunId();
        row.attemptId = operation.attemptId();
        row.status = operation.status().name();
        row.reason = operation.reason();
        row.createdAt = PostgresPersistenceSupport.toDateTime(operation.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(operation.updatedAtEpochMillis());
        return row;
    }

    private ProviderToolOperation toOperation(ProviderToolOperationRow row) {
        return new ProviderToolOperation(
                safe(row.operationId),
                PostgresPersistenceSupport.idString(row.taskId),
                safe(row.stageRunId),
                safe(row.attemptId),
                parseStatus(row.status),
                safe(row.reason),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static ProviderToolOperation.Status parseStatus(String value) {
        try {
            return ProviderToolOperation.Status.valueOf(safe(value));
        } catch (IllegalArgumentException exception) {
            return ProviderToolOperation.Status.UNKNOWN_REMOTE_RESULT;
        }
    }
}
