package com.wish.rd.bootstrap.persistence.impl;

import com.wish.rd.bootstrap.persistence.PostgresPersistenceSupport;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.wish.rd.bootstrap.persistence.entity.RdAgentStageArtifactRow;
import com.wish.rd.bootstrap.persistence.mapper.RdAgentStageArtifactMapper;
import com.wish.rd.engine.agent.model.AgentRole;
import com.wish.rd.engine.agent.model.AgentStageArtifact;
import com.wish.rd.engine.agent.AgentStageArtifactStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * PostgreSQL Agent 阶段产物存储适配器。
 */
@Component
@ConditionalOnProperty(name = "rd.knowledge.store", havingValue = "postgres")
public final class PostgresAgentStageArtifactStore implements AgentStageArtifactStore {

    private final RdAgentStageArtifactMapper mapper;

    public PostgresAgentStageArtifactStore(RdAgentStageArtifactMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AgentStageArtifact save(AgentStageArtifact artifact) {
        mapper.upsertStageArtifact(toRow(artifact));
        return artifact;
    }

    @Override
    public List<AgentStageArtifact> listByTask(String taskId) {
        return mapper.selectList(new QueryWrapper<RdAgentStageArtifactRow>()
                        .eq("task_id", PostgresPersistenceSupport.parseId(taskId)))
                .stream()
                .sorted(Comparator
                        .comparing((RdAgentStageArtifactRow row) -> row.createdAt)
                        .thenComparing(row -> row.id))
                .map(this::toArtifact)
                .toList();
    }

    private RdAgentStageArtifactRow toRow(AgentStageArtifact artifact) {
        RdAgentStageArtifactRow row = new RdAgentStageArtifactRow();
        row.id = PostgresPersistenceSupport.parseId(artifact.artifactId());
        row.stageRunId = PostgresPersistenceSupport.parseId(artifact.stageRunId());
        row.taskId = PostgresPersistenceSupport.parseId(artifact.taskId());
        row.role = artifact.role().name();
        row.artifactType = artifact.artifactType();
        row.artifactUri = artifact.artifactUri();
        row.summary = artifact.summary();
        row.contentPreview = artifact.contentPreview();
        row.contentHash = artifact.contentHash();
        row.metadataJson = artifact.metadataJson();
        row.createdAt = PostgresPersistenceSupport.toDateTime(artifact.createdAtEpochMillis());
        return row;
    }

    private AgentStageArtifact toArtifact(RdAgentStageArtifactRow row) {
        return new AgentStageArtifact(
                PostgresPersistenceSupport.idString(row.id),
                PostgresPersistenceSupport.idString(row.stageRunId),
                PostgresPersistenceSupport.idString(row.taskId),
                AgentRole.valueOf(row.role),
                row.artifactType,
                row.artifactUri,
                row.summary,
                row.contentPreview,
                row.contentHash,
                row.metadataJson,
                PostgresPersistenceSupport.toEpochMillis(row.createdAt)
        );
    }
}
