package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageEventRow;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageRunRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageRunMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageRun;
import com.wish.rd.engine.agent.AgentStageRunStore;
import com.wish.rd.engine.agent.model.AgentStageStatus;
import com.wish.rd.engine.agent.AgentStageTransitions;
import com.wish.rd.framework.id.SnowflakeIdGenerator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL Agent 阶段运行存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public class PostgresAgentStageRunStore implements AgentStageRunStore {

    private final RdAgentStageRunMapper mapper;
    private final SnowflakeIdGenerator idGenerator;

    @Autowired
    public PostgresAgentStageRunStore(RdAgentStageRunMapper mapper) {
        this(mapper, SnowflakeIdGenerator.defaultGenerator());
    }

    public PostgresAgentStageRunStore(RdAgentStageRunMapper mapper, SnowflakeIdGenerator idGenerator) {
        this.mapper = mapper;
        this.idGenerator = idGenerator == null ? SnowflakeIdGenerator.defaultGenerator() : idGenerator;
    }

    @Override
    public AgentStageRun save(AgentStageRun stageRun) {
        RdAgentStageRunRow existing = mapper.selectOne(new QueryWrapper<RdAgentStageRunRow>()
                .eq("task_id", PostgresPersistenceSupport.parseId(stageRun.taskId()))
                .eq("role", stageRun.role().name())
                .eq("idempotency_key", stageRun.idempotencyKey())
                .last("LIMIT 1"));
        if (existing != null && !PostgresPersistenceSupport.idString(existing.id).equals(stageRun.stageRunId())) {
            throw new IllegalStateException("duplicate agent stage idempotency key: " + stageRun.idempotencyKey());
        }
        mapper.upsertStageRun(toRow(stageRun));
        return stageRun;
    }

    @Override
    public Optional<AgentStageRun> findById(String stageRunId) {
        return Optional.ofNullable(mapper.selectById(PostgresPersistenceSupport.parseId(stageRunId)))
                .map(this::toStageRun);
    }

    @Override
    public List<AgentStageRun> listByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<RdAgentStageRunRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((RdAgentStageRunRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toStageRun)
                .toList();
    }

    @Override
    @Transactional
    public AgentStageRun transition(
            String stageRunId,
            AgentStageStatus targetStatus,
            String errorCategory,
            String errorMessage,
            long updateTimeEpochMillis
    ) {
        AgentStageRun existing = findById(stageRunId)
                .orElseThrow(() -> new IllegalArgumentException("agent stage run not found: " + stageRunId));
        AgentStageTransitions.ensureTransition(existing.status(), targetStatus);
        AgentStageRun next = existing.withStatus(targetStatus, errorCategory, errorMessage, updateTimeEpochMillis);
        mapper.upsertStageRun(toRow(next));
        mapper.insertStageEvent(toEventRow(existing, next));
        return next;
    }

    private RdAgentStageEventRow toEventRow(AgentStageRun previous, AgentStageRun next) {
        RdAgentStageEventRow row = new RdAgentStageEventRow();
        row.id = PostgresPersistenceSupport.parseId(idGenerator.nextIdString());
        row.stageRunId = PostgresPersistenceSupport.parseId(next.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(next.taskId());
        row.role = next.role().name();
        row.status = next.status().name();
        row.message = next.errorMessage();
        row.metadataJson = """
                {"sourceStatus":%s,"targetStatus":%s,"errorCategory":%s}
                """.formatted(
                json(previous.status().name()),
                json(next.status().name()),
                json(next.errorCategory())
        ).strip();
        row.enteredAt = PostgresPersistenceSupport.toDateTime(next.updateTimeEpochMillis());
        row.durationMs = Math.max(0, next.updateTimeEpochMillis() - previous.updateTimeEpochMillis());
        row.trigger = "SYSTEM";
        return row;
    }

    private RdAgentStageRunRow toRow(AgentStageRun stageRun) {
        RdAgentStageRunRow row = new RdAgentStageRunRow();
        row.id = PostgresPersistenceSupport.parseId(stageRun.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(stageRun.taskId());
        row.role = stageRun.role().name();
        row.status = stageRun.status().name();
        row.attemptNo = stageRun.attemptNo();
        row.idempotencyKey = stageRun.idempotencyKey();
        row.providerName = stageRun.providerName();
        row.providerAttemptsJson = stageRun.providerAttemptsJson();
        row.contextPackageId = nullableId(stageRun.contextPackageId());
        row.promptArtifactId = nullableId(stageRun.promptArtifactId());
        row.resultArtifactId = nullableId(stageRun.resultArtifactId());
        row.reviewResultJson = stageRun.reviewResultJson();
        row.errorCategory = stageRun.errorCategory();
        row.errorMessage = stageRun.errorMessage();
        row.startedAt = PostgresPersistenceSupport.nullableDateTime(stageRun.startedAtEpochMillis());
        row.finishedAt = PostgresPersistenceSupport.nullableDateTime(stageRun.finishedAtEpochMillis());
        row.createdAt = PostgresPersistenceSupport.toDateTime(stageRun.createTimeEpochMillis());
        row.updatedAt = PostgresPersistenceSupport.toDateTime(stageRun.updateTimeEpochMillis());
        return row;
    }

    private AgentStageRun toStageRun(RdAgentStageRunRow row) {
        return new AgentStageRun(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.taskId),
                AgentRole.valueOf(row.role),
                AgentStageStatus.valueOf(row.status),
                row.attemptNo == null ? 1 : row.attemptNo,
                row.idempotencyKey,
                PostgresPersistenceSupport.idString(row.contextPackageId),
                PostgresPersistenceSupport.idString(row.promptArtifactId),
                PostgresPersistenceSupport.idString(row.resultArtifactId),
                row.providerName,
                row.providerAttemptsJson,
                row.reviewResultJson,
                row.errorCategory,
                row.errorMessage,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.updatedAt),
                PostgresPersistenceSupport.toEpochMillis(row.startedAt),
                PostgresPersistenceSupport.toEpochMillis(row.finishedAt)
        );
    }

    private Long nullableId(String value) {
        String safeValue = value == null ? "" : value.strip();
        return safeValue.isBlank() ? null : PostgresPersistenceSupport.parseId(safeValue);
    }

    private String json(String value) {
        return "\"" + (value == null ? "" : value.strip())
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
