package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.TaskFailureProvenanceRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskFailureProvenanceMapper;
import com.wish.rd.bootstrap.persistence.mapper.RequirementStageFinalizationMapper;
import com.wish.rd.engine.retry.TaskRetryFailureProvenanceStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryFailureProvenance;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/** PostgreSQL reader for the exact failure snapshot used by retry resolution. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresTaskRetryFailureProvenanceStore implements TaskRetryFailureProvenanceStore {

    private final TaskFailureProvenanceMapper mapper;
    private final RequirementStageFinalizationMapper finalizationMapper;

    public PostgresTaskRetryFailureProvenanceStore(
            TaskFailureProvenanceMapper mapper,
            RequirementStageFinalizationMapper finalizationMapper
    ) {
        this.mapper = mapper;
        this.finalizationMapper = finalizationMapper;
    }

    @Override
    public Optional<TaskRetryFailureProvenance> findExact(
            String taskId,
            RdTaskStatus failedTaskStatus,
            long failedTaskVersion,
            long failedTaskFencingToken
    ) {
        if (failedTaskStatus == null) {
            return Optional.empty();
        }
        TaskFailureProvenanceRow row = mapper.findExact(
                PostgresPersistenceSupport.parseId(taskId), failedTaskStatus.name(),
                failedTaskVersion, failedTaskFencingToken);
        if (row == null || finalizationMapper.countCorroboratedFailureProvenance(row) != 1) {
            return Optional.empty();
        }
        return Optional.of(toDomain(row));
    }

    private static TaskRetryFailureProvenance toDomain(TaskFailureProvenanceRow row) {
        return new TaskRetryFailureProvenance(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.failedStageCommandId),
                row.failedCommandAttemptNo == null ? 0 : row.failedCommandAttemptNo,
                safe(row.failedStage),
                enumValue(TaskFailurePhase.class, row.failurePhase, "failure_phase"),
                enumValue(RdTaskStatus.class, row.outcomeStatus, "outcome_status"),
                number(row.failedTaskVersion),
                number(row.failedTaskFencingToken),
                PostgresPersistenceSupport.idString(row.failedStageRunId),
                PostgresPersistenceSupport.idString(row.failedRetrievalRunId),
                PostgresPersistenceSupport.idString(row.failedAiReviewRunId),
                PostgresPersistenceSupport.idString(row.sourcePolicyRunId),
                safe(row.sourcePlanDigest),
                safe(row.publicationOperationId),
                safe(row.failureKind),
                PostgresPersistenceSupport.toEpochMillis(row.recordedAt));
    }

    private static long number(Long value) {
        return value == null ? 0L : value;
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, String column) {
        String normalized = safe(value);
        if (normalized.isBlank()) {
            throw new IllegalStateException("failure provenance " + column + " must not be blank");
        }
        try {
            return Enum.valueOf(type, normalized);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "failure provenance " + column + " is invalid: " + normalized, exception);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
