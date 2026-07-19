package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryCheckpointRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryCheckpointMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.TaskRetryCheckpointStore;
import com.wish.rd.engine.retry.model.TaskFailurePhase;
import com.wish.rd.engine.retry.model.TaskRetryCheckpoint;
import com.wish.rd.engine.retry.model.TaskRetryCheckpointStatus;
import com.wish.rd.rag.runtime.model.RdTaskStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

/** PostgreSQL implementation of idempotent, CAS-updated task retry checkpoints. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresTaskRetryCheckpointStore implements TaskRetryCheckpointStore {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };
    private final TaskRetryCheckpointMapper mapper;

    public PostgresTaskRetryCheckpointStore(TaskRetryCheckpointMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public CreateResult createOrGet(TaskRetryCheckpoint checkpoint) {
        if (checkpoint == null) {
            throw new IllegalArgumentException("retry checkpoint must not be null");
        }
        int inserted = mapper.insertIfAbsent(toRow(checkpoint));
        if (inserted == 1) {
            return new CreateResult(checkpoint, true);
        }
        TaskRetryCheckpointRow existing = mapper.selectByIdempotencyKey(checkpoint.idempotencyKey());
        if (existing == null) {
            throw new IllegalStateException("retry checkpoint conflict without existing row: "
                    + checkpoint.idempotencyKey());
        }
        return new CreateResult(toCheckpoint(existing), false);
    }

    @Override
    public Optional<TaskRetryCheckpoint> find(String checkpointId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(checkpointId)))
                .map(this::toCheckpoint);
    }

    @Override
    public Optional<TaskRetryCheckpoint> findActiveByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<TaskRetryCheckpointRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId))
                        .in("status", TaskRetryCheckpointStatus.CREATED.name(),
                                TaskRetryCheckpointStatus.DISPATCHED.name()))
                .stream()
                .max(Comparator.comparing((TaskRetryCheckpointRow row) -> row.attemptNo)
                        .thenComparing(row -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toCheckpoint);
    }

    @Override
    public List<TaskRetryCheckpoint> listByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<TaskRetryCheckpointRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId))
                        .orderByAsc("attempt_no", "created_at", "id"))
                .stream().map(this::toCheckpoint).toList();
    }

    @Override
    @Transactional
    public TaskRetryCheckpoint transition(
            String checkpointId,
            TaskRetryCheckpointStatus expectedStatus,
            TaskRetryCheckpointStatus targetStatus,
            String errorMessage,
            long nowEpochMillis
    ) {
        TaskRetryCheckpoint current = find(checkpointId)
                .orElseThrow(() -> new NoSuchElementException("retry checkpoint not found: " + checkpointId));
        if (current.status() != expectedStatus) {
            throw new IllegalStateException("stale retry checkpoint status: expected " + expectedStatus
                    + " but was " + current.status());
        }
        ensureTransition(current.status(), targetStatus);
        TaskRetryCheckpoint updated = current.withStatus(targetStatus, safe(errorMessage), nowEpochMillis);
        TaskRetryCheckpointRow row = toRow(updated);
        row.expectedStatus = expectedStatus.name();
        if (mapper.transition(row) != 1) {
            throw new IllegalStateException("retry checkpoint compare-and-set failed: " + checkpointId);
        }
        return updated;
    }

    private static void ensureTransition(TaskRetryCheckpointStatus source, TaskRetryCheckpointStatus target) {
        if (source == null || target == null || source.isTerminal()) {
            throw new IllegalStateException("terminal or null retry checkpoint cannot transition");
        }
        boolean allowed = source == TaskRetryCheckpointStatus.CREATED
                ? target == TaskRetryCheckpointStatus.DISPATCHED
                    || target == TaskRetryCheckpointStatus.FAILED_RETRYABLE
                    || target == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                    || target == TaskRetryCheckpointStatus.CANCELLED
                : source == TaskRetryCheckpointStatus.DISPATCHED
                    && (target == TaskRetryCheckpointStatus.SUCCEEDED
                    || target == TaskRetryCheckpointStatus.FAILED_RETRYABLE
                    || target == TaskRetryCheckpointStatus.FAILED_NEEDS_HUMAN
                    || target == TaskRetryCheckpointStatus.CANCELLED);
        if (!allowed) {
            throw new IllegalStateException("illegal retry checkpoint transition: " + source + " -> " + target);
        }
    }

    private TaskRetryCheckpointRow toRow(TaskRetryCheckpoint checkpoint) {
        TaskRetryCheckpointRow row = new TaskRetryCheckpointRow();
        row.id = PostgresPersistenceSupport.parseId(checkpoint.checkpointId());
        row.taskId = PostgresPersistenceSupport.parseId(checkpoint.taskId());
        row.failurePhase = checkpoint.failurePhase().name();
        row.retryFromRole = checkpoint.retryFromRole() == null ? "" : checkpoint.retryFromRole().name();
        row.failedStageRunId = nullableId(checkpoint.failedStageRunId());
        row.failedRetrievalRunId = nullableId(checkpoint.failedRetrievalRunId());
        row.failedAiReviewRunId = nullableId(checkpoint.failedAiReviewRunId());
        row.attemptNo = checkpoint.attemptNo();
        row.idempotencyKey = checkpoint.idempotencyKey();
        row.sourceTaskStatus = checkpoint.sourceTaskStatus().name();
        row.sourceTaskVersion = checkpoint.sourceTaskVersion();
        row.operatorNote = checkpoint.operatorNote();
        row.evidenceMaterialIdsJson = writeEvidenceMaterialIds(checkpoint.evidenceMaterialIds());
        row.status = checkpoint.status().name();
        row.reason = checkpoint.reason();
        row.errorMessage = checkpoint.errorMessage();
        row.createdAt = PostgresPersistenceSupport.toDateTime(checkpoint.createdAtEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(checkpoint.updatedAtEpochMillis());
        return row;
    }

    private TaskRetryCheckpoint toCheckpoint(TaskRetryCheckpointRow row) {
        return new TaskRetryCheckpoint(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                enumValue(TaskFailurePhase.class, row.failurePhase, TaskFailurePhase.AGENT_ROLE),
                enumValue(AgentRole.class, row.retryFromRole, null),
                PostgresPersistenceSupport.idString(row.failedStageRunId),
                PostgresPersistenceSupport.idString(row.failedRetrievalRunId),
                PostgresPersistenceSupport.idString(row.failedAiReviewRunId),
                row.attemptNo == null ? 1 : row.attemptNo,
                safe(row.idempotencyKey),
                enumValue(RdTaskStatus.class, row.sourceTaskStatus, RdTaskStatus.FAILED_RETRYABLE),
                row.sourceTaskVersion == null ? 0L : row.sourceTaskVersion,
                safe(row.operatorNote),
                readEvidenceMaterialIds(row.evidenceMaterialIdsJson),
                enumValue(TaskRetryCheckpointStatus.class, row.status, TaskRetryCheckpointStatus.CREATED),
                safe(row.reason), safe(row.errorMessage),
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt)
        );
    }

    private static Long nullableId(String value) {
        String normalized = safe(value);
        return normalized.isBlank() ? null : PostgresPersistenceSupport.parseId(normalized);
    }

    private static String writeEvidenceMaterialIds(List<String> materialIds) {
        try {
            return OBJECT_MAPPER.writeValueAsString(materialIds == null ? List.of() : materialIds);
        } catch (Exception exception) {
            throw new IllegalStateException("cannot serialize retry evidence material IDs", exception);
        }
    }

    private static List<String> readEvidenceMaterialIds(String value) {
        try {
            JsonNode root = OBJECT_MAPPER.readTree(value == null ? "[]" : value);
            if (root == null || !root.isArray()) {
                return List.of();
            }
            List<String> materialIds = OBJECT_MAPPER.convertValue(root, STRING_LIST);
            return new LinkedHashSet<>(materialIds == null ? List.<String>of() : materialIds).stream()
                    .map(PostgresTaskRetryCheckpointStore::safe)
                    .filter(materialId -> !materialId.isBlank())
                    .toList();
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private static <T extends Enum<T>> T enumValue(Class<T> type, String value, T fallback) {
        try {
            return value == null || value.isBlank() ? fallback : Enum.valueOf(type, value);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
