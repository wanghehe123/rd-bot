package com.wish.rd.bootstrap.persistence.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;
import com.wish.rd.bootstrap.persistence.entity.AgentPrivateArtifactRow;
import com.wish.rd.bootstrap.persistence.mapper.AgentPrivateArtifactMapper;
import com.wish.rd.exec.repair.execution.model.RepairArtifactType;
import com.wish.rd.exec.repair.pi.AgentPrivateArtifactIndex;
import com.wish.rd.exec.repair.pi.model.AgentPrivateArtifactRecord;
import com.wish.rd.exec.repair.execution.model.RepairArtifact;
import com.wish.rd.rag.project.agent.model.AgentExecutionProfileSnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** PostgreSQL-backed index for restricted Pi objects. */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentPrivateArtifactIndex implements AgentPrivateArtifactIndex {

    private final AgentPrivateArtifactMapper mapper;

    public PostgresAgentPrivateArtifactIndex(AgentPrivateArtifactMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void record(AgentExecutionProfileSnapshot snapshot, RepairArtifact artifact) {
        mapper.upsert(toRow(AgentPrivateArtifactRecord.from(snapshot, artifact)));
    }

    @Override
    public List<AgentPrivateArtifactRecord> findExpired(long nowEpochMillis, int limit) {
        int boundedLimit = Math.max(1, Math.min(1000, limit <= 0 ? 100 : limit));
        return mapper.selectList(new QueryWrapper<AgentPrivateArtifactRow>()
                        .le("expires_at", PostgresPersistenceSupport.toDateTime(nowEpochMillis))
                        .orderByAsc("expires_at")
                        .last("LIMIT " + boundedLimit))
                .stream()
                .sorted(Comparator.comparingLong(row -> PostgresPersistenceSupport.toEpochMillis(row.expiresAt)))
                .map(this::toRecord)
                .toList();
    }

    @Override
    public void remove(String artifactId) {
        if (artifactId != null && !artifactId.isBlank()) {
            mapper.deleteById(artifactId.strip());
        }
    }

    private static AgentPrivateArtifactRow toRow(AgentPrivateArtifactRecord record) {
        AgentPrivateArtifactRow row = new AgentPrivateArtifactRow();
        row.artifactId = record.artifactId();
        row.taskId = PostgresPersistenceSupport.parseId(record.taskId());
        row.stageRunId = PostgresPersistenceSupport.parseId(record.stageRunId());
        row.snapshotId = record.snapshotId();
        row.artifactType = record.artifactType().name();
        row.artifactName = record.artifactName();
        row.artifactUri = record.artifactUri();
        row.bytes = record.bytes();
        row.sha256 = record.sha256();
        row.contentType = record.contentType();
        row.retentionClass = record.retentionClass();
        row.createdAt = PostgresPersistenceSupport.toDateTime(record.createdAtEpochMillis());
        row.expiresAt = PostgresPersistenceSupport.toDateTime(record.expiresAtEpochMillis());
        return row;
    }

    private AgentPrivateArtifactRecord toRecord(AgentPrivateArtifactRow row) {
        RepairArtifactType type;
        try {
            type = RepairArtifactType.valueOf(row.artifactType);
        } catch (IllegalArgumentException exception) {
            type = RepairArtifactType.OTHER;
        }
        return new AgentPrivateArtifactRecord(
                row.artifactId,
                PostgresPersistenceSupport.idString(row.taskId),
                PostgresPersistenceSupport.idString(row.stageRunId),
                row.snapshotId,
                type,
                row.artifactName,
                row.artifactUri,
                row.bytes == null ? 0L : row.bytes,
                row.sha256,
                row.contentType,
                row.retentionClass,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt),
                PostgresPersistenceSupport.toEpochMillis(row.expiresAt)
        );
    }
}
