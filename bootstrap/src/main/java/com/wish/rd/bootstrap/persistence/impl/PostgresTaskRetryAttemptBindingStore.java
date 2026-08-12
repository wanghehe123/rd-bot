package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.TaskRetryAttemptBindingRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskRetryAttemptBindingMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.retry.TaskRetryAttemptBindingStore;
import com.wish.rd.engine.retry.model.TaskRetryAttemptBinding;
import com.wish.rd.engine.retry.model.TaskRetryAttemptKind;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/** PostgreSQL implementation of immutable, same-checkpoint retry attempt bindings. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresTaskRetryAttemptBindingStore implements TaskRetryAttemptBindingStore {

    private final TaskRetryAttemptBindingMapper mapper;

    public PostgresTaskRetryAttemptBindingStore(TaskRetryAttemptBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public TaskRetryAttemptBinding save(TaskRetryAttemptBinding binding) {
        if (binding == null) {
            throw new IllegalArgumentException("retry attempt binding must not be null");
        }
        validateParent(binding);
        mapper.insertIfAbsent(toRow(binding));
        TaskRetryAttemptBinding effective = findById(binding.bindingId())
                .orElseThrow(() -> new IllegalStateException("retry attempt binding insert did not return a row"));
        if (!same(binding, effective)) {
            throw new IllegalStateException("retry attempt binding immutable conflict: " + binding.bindingId());
        }
        return effective;
    }

    @Override
    public Optional<TaskRetryAttemptBinding> findById(String bindingId) {
        return Optional.ofNullable(mapper.findById(PostgresPersistenceSupport.parseId(bindingId))).map(this::toBinding);
    }

    @Override
    public List<TaskRetryAttemptBinding> listByCheckpoint(String checkpointId) {
        return mapper.listByCheckpoint(PostgresPersistenceSupport.parseId(checkpointId)).stream()
                .map(this::toBinding).toList();
    }

    @Override
    public Optional<TaskRetryAttemptBinding> findPrimary(
            String checkpointId, TaskRetryAttemptKind kind, AgentRole role
    ) {
        List<TaskRetryAttemptBinding> rows = mapper.findPrimary(
                        PostgresPersistenceSupport.parseId(checkpointId), kind.name(), role == null ? "" : role.name())
                .stream().map(this::toBinding).toList();
        if (rows.size() > 1) {
            throw new IllegalStateException("retry primary binding is ambiguous for checkpoint: " + checkpointId);
        }
        return rows.stream().findFirst();
    }

    @Override
    public Optional<TaskRetryAttemptBinding> findChild(
            String checkpointId, String parentBindingId, TaskRetryAttemptKind kind, int ordinal
    ) {
        List<TaskRetryAttemptBinding> rows = mapper.findChild(
                        PostgresPersistenceSupport.parseId(checkpointId),
                        PostgresPersistenceSupport.parseId(parentBindingId), kind.name(), ordinal)
                .stream().map(this::toBinding).toList();
        if (rows.size() > 1) {
            throw new IllegalStateException("retry child binding is ambiguous for checkpoint: " + checkpointId);
        }
        return rows.stream().findFirst();
    }

    private static TaskRetryAttemptBindingRow toRow(TaskRetryAttemptBinding binding) {
        TaskRetryAttemptBindingRow row = new TaskRetryAttemptBindingRow();
        row.id = PostgresPersistenceSupport.parseId(binding.bindingId());
        row.checkpointId = PostgresPersistenceSupport.parseId(binding.checkpointId());
        row.bindingKind = binding.kind().name();
        row.role = binding.role() == null ? "" : binding.role().name();
        switch (binding.kind()) {
            case AGENT_STAGE -> row.stageRunId = PostgresPersistenceSupport.parseId(binding.attemptId());
            case RETRIEVAL -> row.retrievalRunId = PostgresPersistenceSupport.parseId(binding.attemptId());
            case AI_REVIEW -> row.aiReviewRunId = PostgresPersistenceSupport.parseId(binding.attemptId());
        }
        row.parentBindingId = binding.parentBindingId().isBlank()
                ? null : PostgresPersistenceSupport.parseId(binding.parentBindingId());
        row.attemptNo = binding.attemptNo();
        row.ordinal = binding.ordinal();
        row.createdAt = OffsetDateTime.now();
        return row;
    }

    private TaskRetryAttemptBinding toBinding(TaskRetryAttemptBindingRow row) {
        TaskRetryAttemptKind kind = TaskRetryAttemptKind.valueOf(row.bindingKind);
        Long attemptId = switch (kind) {
            case AGENT_STAGE -> row.stageRunId;
            case RETRIEVAL -> row.retrievalRunId;
            case AI_REVIEW -> row.aiReviewRunId;
        };
        if (attemptId == null) {
            throw new IllegalStateException("retry attempt binding has no typed target: " + row.id);
        }
        AgentRole role = row.role == null || row.role.isBlank() ? null : AgentRole.valueOf(row.role);
        return new TaskRetryAttemptBinding(PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.checkpointId), kind, role,
                PostgresPersistenceSupport.idString(attemptId), PostgresPersistenceSupport.idString(row.parentBindingId),
                row.attemptNo == null ? 0 : row.attemptNo, row.ordinal == null ? -1 : row.ordinal);
    }

    private static boolean same(TaskRetryAttemptBinding left, TaskRetryAttemptBinding right) {
        return left.bindingId().equals(right.bindingId()) && left.checkpointId().equals(right.checkpointId())
                && left.kind() == right.kind() && left.role() == right.role()
                && left.attemptId().equals(right.attemptId()) && left.parentBindingId().equals(right.parentBindingId())
                && left.attemptNo() == right.attemptNo() && left.ordinal() == right.ordinal();
    }

    private void validateParent(TaskRetryAttemptBinding binding) {
        if (binding.parentBindingId().isBlank()) {
            return;
        }
        TaskRetryAttemptBinding parent = findById(binding.parentBindingId()).orElseThrow(() ->
                new IllegalStateException("retry child binding parent not found: " + binding.parentBindingId()));
        if (!parent.checkpointId().equals(binding.checkpointId())
                || parent.kind() != TaskRetryAttemptKind.AGENT_STAGE
                || parent.role() != binding.role()) {
            throw new IllegalStateException("retry child binding has a foreign parent identity");
        }
    }
}
