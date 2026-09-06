package com.wish.rd.bootstrap.persistence.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.TaskManagerDecisionRow;
import com.wish.rd.bootstrap.persistence.mapper.TaskManagerDecisionMapper;
import com.wish.rd.engine.requirement.manager.ManagerDecision;
import com.wish.rd.engine.requirement.manager.ManagerDecisionStore;
import com.wish.rd.engine.requirement.manager.ManagerRoute;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL Manager decision store.
 *
 * <p>Must not be {@code final}: {@code @Transactional} callers may CGLIB-subclass sibling beans.
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresManagerDecisionStore implements ManagerDecisionStore {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final TaskManagerDecisionMapper mapper;

    public PostgresManagerDecisionStore(TaskManagerDecisionMapper mapper) {
        this.mapper = java.util.Objects.requireNonNull(mapper, "mapper must not be null");
    }

    @Override
    public ManagerDecision insertIfAbsent(ManagerDecision decision) {
        if (decision == null) {
            throw new IllegalArgumentException("decision is required");
        }
        TaskManagerDecisionRow row = toRow(decision);
        mapper.insertIfAbsent(row);
        return findBySourceCommand(decision.taskId(), decision.sourceCommandId())
                .orElseThrow(() -> new IllegalStateException(
                        "manager decision was not persisted: " + decision.sourceCommandId()));
    }

    @Override
    public Optional<ManagerDecision> findLatest(String taskId) {
        return Optional.ofNullable(mapper.findLatest(PostgresPersistenceSupport.parseId(taskId)))
                .map(this::toDecision);
    }

    @Override
    public Optional<ManagerDecision> findBySourceCommand(String taskId, String sourceCommandId) {
        return Optional.ofNullable(mapper.findBySourceCommand(
                        PostgresPersistenceSupport.parseId(taskId),
                        PostgresPersistenceSupport.parseId(sourceCommandId)))
                .map(this::toDecision);
    }

    @Override
    public List<ManagerDecision> listByTask(String taskId) {
        List<ManagerDecision> decisions = new ArrayList<>();
        for (TaskManagerDecisionRow row : mapper.listByTask(PostgresPersistenceSupport.parseId(taskId))) {
            decisions.add(toDecision(row));
        }
        return List.copyOf(decisions);
    }

    @Override
    public int maxRound(String taskId) {
        return mapper.maxRound(PostgresPersistenceSupport.parseId(taskId));
    }

    private TaskManagerDecisionRow toRow(ManagerDecision decision) {
        TaskManagerDecisionRow row = new TaskManagerDecisionRow();
        row.taskId = PostgresPersistenceSupport.parseId(decision.taskId());
        row.roundNo = decision.roundNo();
        row.sourceCommandId = PostgresPersistenceSupport.parseId(decision.sourceCommandId());
        row.stateVersion = decision.stateVersion();
        row.stateHash = decision.stateHash();
        row.route = decision.route().name();
        try {
            row.targetRecordIdsJson = MAPPER.writeValueAsString(decision.targetRecordIds());
        } catch (JsonProcessingException invalid) {
            throw new IllegalArgumentException("targetRecordIds cannot be encoded", invalid);
        }
        row.boundedContract = decision.boundedContract();
        row.executorRoute = decision.executorRoute();
        row.rationale = decision.rationale();
        row.decisionHash = decision.decisionHash();
        row.createdAt = OffsetDateTime.now();
        return row;
    }

    private ManagerDecision toDecision(TaskManagerDecisionRow row) {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode node = MAPPER.readTree(row.targetRecordIdsJson == null ? "[]" : row.targetRecordIdsJson);
            if (node.isArray()) {
                node.forEach(item -> {
                    String value = item.asText("").strip();
                    if (!value.isBlank()) {
                        ids.add(value);
                    }
                });
            }
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("invalid manager targetRecordIds JSON", invalid);
        }
        return new ManagerDecision(
                PostgresPersistenceSupport.idString(row.taskId),
                row.roundNo == null ? 1 : row.roundNo,
                PostgresPersistenceSupport.idString(row.sourceCommandId),
                row.stateVersion == null ? 1L : row.stateVersion,
                row.stateHash,
                ManagerRoute.valueOf(row.route),
                ids,
                row.boundedContract,
                row.executorRoute,
                row.rationale,
                row.decisionHash);
    }
}
